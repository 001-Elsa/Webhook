package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.DeliveryStatus;
import com.example.webhook.platform.domain.TenantQuota;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.TenantQuotaRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TenantQuotaService {
    private static final long CACHE_TTL_MS = 5_000L;
    /** Token bucket: refill `rate` tokens/sec up to `capacity`, consume 1 if available. */
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local capacity = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now_ms = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local data = redis.call('HMGET', key, 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local last_ts = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              last_ts = now_ms
            end
            local elapsed = math.max(0, now_ms - last_ts) / 1000.0
            tokens = math.min(capacity, tokens + elapsed * rate)
            local allowed = 0
            if tokens >= requested then
              tokens = tokens - requested
              allowed = 1
            end
            redis.call('HMSET', key, 'tokens', tokens, 'ts', now_ms)
            redis.call('EXPIRE', key, math.max(2, math.ceil(capacity / math.max(rate, 0.001)) + 2))
            return allowed
            """, Long.class);

    private final TenantQuotaRepository quotas;
    private final DeliveryTaskRepository deliveries;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<String, CachedInt> concurrencyCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CachedInt> weightCache = new ConcurrentHashMap<>();

    public TenantQuotaService(TenantQuotaRepository quotas, DeliveryTaskRepository deliveries,
                              StringRedisTemplate redis, JdbcTemplate jdbc, MeterRegistry metrics) {
        this.quotas = quotas;
        this.deliveries = deliveries;
        this.redis = redis;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /** Runs inside event ingestion's transaction; an exceeded daily quota rolls usage back. */
    public void reserveIngress(String tenantId, int deliveryCount, int payloadBytes) {
        TenantQuota quota = quotas.findById(tenantId).orElseGet(() -> defaults(tenantId));
        checkIngressRate(tenantId, quota.getIngressPerSecond());
        long pending = deliveries.countByEventTenantIdAndStatusIn(tenantId,
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING));
        if (pending + deliveryCount > quota.getMaxPendingDeliveries()) reject(tenantId, "pending_deliveries");

        LocalDate date = LocalDate.now(ZoneOffset.UTC);
        jdbc.update("""
                insert into tenant_daily_usage
                    (tenant_id, usage_date, accepted_events, payload_bytes, updated_at)
                values (?, ?, 1, ?, ?)
                on duplicate key update accepted_events=accepted_events+1,
                    payload_bytes=payload_bytes+values(payload_bytes), updated_at=values(updated_at)
                """, tenantId, Date.valueOf(date), payloadBytes, Timestamp.from(Instant.now()));
        var usage = jdbc.queryForMap("""
                select accepted_events, payload_bytes from tenant_daily_usage
                 where tenant_id=? and usage_date=?
                """, tenantId, Date.valueOf(date));
        if (((Number) usage.get("accepted_events")).longValue() > quota.getDailyEventLimit()) {
            reject(tenantId, "daily_events");
        }
        if (((Number) usage.get("payload_bytes")).longValue() > quota.getDailyPayloadBytes()) {
            reject(tenantId, "daily_payload_bytes");
        }
    }

    public int concurrencyLimit(String tenantId) {
        return cached(concurrencyCache, tenantId, 32,
                id -> quotas.findById(id).map(TenantQuota::getMaxConcurrentDeliveries).orElse(32));
    }

    public int schedulingWeight(String tenantId) {
        return cached(weightCache, tenantId, 1,
                id -> quotas.findById(id).map(TenantQuota::getSchedulingWeight).orElse(1));
    }

    public void invalidateCache(String tenantId) {
        concurrencyCache.remove(tenantId);
        weightCache.remove(tenantId);
    }

    private void checkIngressRate(String tenantId, int limit) {
        try {
            int capacity = Math.max(1, limit);
            Long allowed = redis.execute(TOKEN_BUCKET_SCRIPT,
                    List.of("eventrelay:quota:ingress:" + tenantId),
                    String.valueOf(capacity),
                    String.valueOf(capacity),
                    String.valueOf(Instant.now().toEpochMilli()),
                    "1");
            if (allowed != null && allowed == 0L) reject(tenantId, "ingress_rate");
        } catch (DataAccessException unavailable) {
            metrics.counter("webhook.quota.degraded", "dependency", "redis").increment();
            // Daily and backlog quotas remain authoritative; Redis rate quota is fail-open.
        }
    }

    private int cached(ConcurrentHashMap<String, CachedInt> cache, String tenantId, int fallback,
                       java.util.function.ToIntFunction<String> loader) {
        long now = System.currentTimeMillis();
        CachedInt hit = cache.get(tenantId);
        if (hit != null && hit.expiresAtMs > now) return hit.value;
        int value = Math.max(1, loader.applyAsInt(tenantId));
        cache.put(tenantId, new CachedInt(value, now + CACHE_TTL_MS));
        return value;
    }

    private TenantQuota defaults(String tenantId) {
        TenantQuota quota = new TenantQuota();
        quota.setTenantId(tenantId);
        return quota;
    }

    private void reject(String tenantId, String reason) {
        metrics.counter("webhook.quota.rejected", "reason", reason).increment();
        throw new QuotaExceededException("Tenant quota exceeded: " + reason);
    }

    private record CachedInt(int value, long expiresAtMs) { }

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) { super(message); }
    }
}

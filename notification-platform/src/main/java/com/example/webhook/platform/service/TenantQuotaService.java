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

@Service
public class TenantQuotaService {
    private static final DefaultRedisScript<Long> INGRESS_SCRIPT = new DefaultRedisScript<>("""
            local value = redis.call('INCR', KEYS[1])
            if value == 1 then redis.call('EXPIRE', KEYS[1], 2) end
            return value
            """, Long.class);
    private final TenantQuotaRepository quotas;
    private final DeliveryTaskRepository deliveries;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;

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
        checkPerSecond(tenantId, quota.getIngressPerSecond());
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
        if (((Number) usage.get("payload_bytes")).longValue() > quota.getPayloadStorageBytes()) {
            reject(tenantId, "payload_storage");
        }
    }

    public int concurrencyLimit(String tenantId) {
        return quotas.findById(tenantId).map(TenantQuota::getMaxConcurrentDeliveries).orElse(32);
    }

    public int schedulingWeight(String tenantId) {
        return quotas.findById(tenantId).map(TenantQuota::getSchedulingWeight).orElse(1);
    }

    private void checkPerSecond(String tenantId, int limit) {
        try {
            long epochSecond = Instant.now().getEpochSecond();
            Long used = redis.execute(INGRESS_SCRIPT,
                    List.of("eventrelay:quota:ingress:" + tenantId + ":" + epochSecond));
            if (used != null && used > limit) reject(tenantId, "ingress_rate");
        } catch (DataAccessException unavailable) {
            metrics.counter("webhook.quota.degraded", "dependency", "redis").increment();
            // Daily and backlog quotas remain authoritative; Redis rate quota is fail-open.
        }
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

    public static class QuotaExceededException extends RuntimeException {
        public QuotaExceededException(String message) { super(message); }
    }
}

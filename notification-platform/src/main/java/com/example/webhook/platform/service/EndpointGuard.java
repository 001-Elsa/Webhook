package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.CircuitState;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local bulkheads are per worker replica; optional Redis slots cap tenant
 * concurrency across the cluster. Circuit state (CLOSED/OPEN/HALF_OPEN) is
 * shared via MySQL so all workers observe the same open/probe window.
 */
@Component
public class EndpointGuard {
    private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local current = tonumber(redis.call('GET', key) or '0')
            if current >= limit then return 0 end
            redis.call('INCR', key)
            redis.call('EXPIRE', key, 120)
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            local key = KEYS[1]
            local current = tonumber(redis.call('GET', key) or '0')
            if current <= 1 then redis.call('DEL', key) else redis.call('DECR', key) end
            return 1
            """, Long.class);

    private final TenantQuotaService quotas;
    private final WebhookEndpointRepository endpoints;
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final boolean globalConcurrencyEnabled;
    private final ConcurrentHashMap<Long, LimitSemaphore> endpointLimits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LimitSemaphore> tenantLimits = new ConcurrentHashMap<>();

    public EndpointGuard(TenantQuotaService quotas, WebhookEndpointRepository endpoints,
                         StringRedisTemplate redis, MeterRegistry metrics,
                         @Value("${webhook.tenant.global-concurrency-enabled:false}") boolean globalConcurrencyEnabled) {
        this.quotas = quotas;
        this.endpoints = endpoints;
        this.redis = redis;
        this.metrics = metrics;
        this.globalConcurrencyEnabled = globalConcurrencyEnabled;
    }

    public Permit tryAcquire(String tenantId, WebhookEndpoint endpoint) {
        Instant now = Instant.now();
        if (!endpoint.isActive() || endpoint.getPausedAt() != null) return Permit.rejected("PAUSED");

        String circuitRejection = evaluateCircuit(endpoint, now);
        if (circuitRejection != null) {
            metrics.counter("webhook.endpoint.guard.rejected", "reason", circuitRejection.toLowerCase()).increment();
            return Permit.rejected(circuitRejection);
        }

        boolean globalHeld = false;
        if (globalConcurrencyEnabled) {
            try {
                Long ok = redis.execute(ACQUIRE,
                        List.of("eventrelay:bulkhead:tenant:" + tenantId),
                        String.valueOf(Math.max(1, quotas.concurrencyLimit(tenantId))));
                if (ok == null || ok == 0L) return Permit.rejected("TENANT_GLOBAL_BULKHEAD");
                globalHeld = true;
            } catch (DataAccessException unavailable) {
                metrics.counter("webhook.tenant.bulkhead.degraded", "dependency", "redis").increment();
            }
        }

        LimitSemaphore tenant = tenantSlot(tenantId);
        if (!tenant.semaphore().tryAcquire()) {
            releaseGlobal(tenantId, globalHeld);
            return Permit.rejected("TENANT_BULKHEAD");
        }
        LimitSemaphore endpointSlot = endpointSlot(endpoint);
        if (!endpointSlot.semaphore().tryAcquire()) {
            tenant.semaphore().release();
            releaseGlobal(tenantId, globalHeld);
            return Permit.rejected("ENDPOINT_BULKHEAD");
        }
        boolean finalGlobal = globalHeld;
        return new Permit(true, null, () -> {
            endpointSlot.semaphore().release();
            tenant.semaphore().release();
            releaseGlobal(tenantId, finalGlobal);
        });
    }

    private String evaluateCircuit(WebhookEndpoint endpoint, Instant now) {
        CircuitState state = endpoint.getCircuitState();
        if (state == null || state == CircuitState.CLOSED) return null;

        if (state == CircuitState.OPEN) {
            if (endpoint.getCircuitOpenUntil() != null && endpoint.getCircuitOpenUntil().isAfter(now)) {
                return "CIRCUIT_OPEN";
            }
            int transitioned = endpoints.transitionOpenToHalfOpen(endpoint.getId(), CircuitState.OPEN,
                    CircuitState.HALF_OPEN, now);
            WebhookEndpoint fresh = endpoints.findById(endpoint.getId()).orElse(endpoint);
            if (transitioned == 1) {
                endpoint.setCircuitState(CircuitState.HALF_OPEN);
                endpoint.setHalfOpenProbes(0);
                metrics.counter("webhook.endpoint.circuit.state", "state", "HALF_OPEN").increment();
                state = CircuitState.HALF_OPEN;
            } else if (fresh.getCircuitState() == CircuitState.HALF_OPEN) {
                endpoint.setCircuitState(CircuitState.HALF_OPEN);
                endpoint.setHalfOpenProbes(fresh.getHalfOpenProbes());
                state = CircuitState.HALF_OPEN;
            } else if (fresh.getCircuitState() == CircuitState.OPEN) {
                return "CIRCUIT_OPEN";
            } else {
                state = fresh.getCircuitState();
                endpoint.setCircuitState(state);
                endpoint.setHalfOpenProbes(fresh.getHalfOpenProbes());
            }
        }

        if (state == CircuitState.HALF_OPEN) {
            WebhookEndpoint fresh = endpoints.findById(endpoint.getId()).orElse(endpoint);
            if (fresh.getCircuitState() != CircuitState.HALF_OPEN) {
                return fresh.getCircuitState() == CircuitState.OPEN ? "CIRCUIT_OPEN" : null;
            }
            int max = Math.max(1, fresh.getHalfOpenMaxProbes());
            if (endpoints.acquireHalfOpenProbe(endpoint.getId(), CircuitState.HALF_OPEN, max) != 1) {
                return "CIRCUIT_OPEN";
            }
            endpoint.setCircuitState(CircuitState.HALF_OPEN);
            endpoint.setHalfOpenProbes(Math.min(max, fresh.getHalfOpenProbes() + 1));
            return null;
        }
        return null;
    }

    private LimitSemaphore tenantSlot(String tenantId) {
        int desired = Math.max(1, quotas.concurrencyLimit(tenantId));
        return refresh(tenantLimits, tenantId, desired);
    }

    private LimitSemaphore endpointSlot(WebhookEndpoint endpoint) {
        int desired = Math.max(1, endpoint.getMaxConcurrency());
        return refresh(endpointLimits, endpoint.getId(), desired);
    }

    private <K> LimitSemaphore refresh(ConcurrentHashMap<K, LimitSemaphore> map, K key, int desired) {
        LimitSemaphore current = map.computeIfAbsent(key, ignored -> new LimitSemaphore(desired));
        if (current.permits.get() == desired) return current;
        if (current.semaphore.availablePermits() == current.permits.get()) {
            LimitSemaphore replacement = new LimitSemaphore(desired);
            map.put(key, replacement);
            return replacement;
        }
        return current;
    }

    private void releaseGlobal(String tenantId, boolean held) {
        if (!held) return;
        try {
            redis.execute(RELEASE, List.of("eventrelay:bulkhead:tenant:" + tenantId));
        } catch (DataAccessException ignored) {
            metrics.counter("webhook.tenant.bulkhead.degraded", "dependency", "redis").increment();
        }
    }

    private static final class LimitSemaphore {
        private final Semaphore semaphore;
        private final AtomicInteger permits;

        LimitSemaphore(int permits) {
            this.permits = new AtomicInteger(permits);
            this.semaphore = new Semaphore(permits, true);
        }

        Semaphore semaphore() { return semaphore; }
    }

    public static final class Permit implements AutoCloseable {
        private final boolean acquired;
        private final String rejectionReason;
        private final Runnable release;
        private boolean closed;

        private Permit(boolean acquired, String rejectionReason, Runnable release) {
            this.acquired = acquired;
            this.rejectionReason = rejectionReason;
            this.release = release;
        }

        static Permit rejected(String reason) { return new Permit(false, reason, () -> { }); }
        public static Permit acquiredNoop() { return new Permit(true, null, () -> { }); }
        public boolean acquired() { return acquired; }
        public String rejectionReason() { return rejectionReason; }
        @Override public void close() {
            if (!closed) {
                closed = true;
                release.run();
            }
        }
    }
}

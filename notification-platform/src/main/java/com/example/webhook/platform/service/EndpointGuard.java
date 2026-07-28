package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.WebhookEndpoint;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Local bulkheads are intentionally per worker replica. Database circuit state
 * is shared, while tenant and endpoint semaphores prevent one noisy target from
 * consuming every HTTP worker on a replica.
 */
@Component
public class EndpointGuard {
    private final TenantQuotaService quotas;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<Long, Semaphore> endpointLimits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> tenantLimits = new ConcurrentHashMap<>();

    public EndpointGuard(TenantQuotaService quotas, MeterRegistry metrics) {
        this.quotas = quotas;
        this.metrics = metrics;
    }

    public Permit tryAcquire(String tenantId, WebhookEndpoint endpoint) {
        Instant now = Instant.now();
        if (!endpoint.isActive() || endpoint.getPausedAt() != null) return Permit.rejected("PAUSED");
        if (endpoint.getCircuitOpenUntil() != null && endpoint.getCircuitOpenUntil().isAfter(now)) {
            metrics.counter("webhook.endpoint.guard.rejected", "reason", "circuit_open").increment();
            return Permit.rejected("CIRCUIT_OPEN");
        }
        Semaphore tenant = tenantLimits.computeIfAbsent(tenantId,
                ignored -> new Semaphore(Math.max(1, quotas.concurrencyLimit(tenantId)), true));
        if (!tenant.tryAcquire()) return Permit.rejected("TENANT_BULKHEAD");
        Semaphore endpointSemaphore = endpointLimits.computeIfAbsent(endpoint.getId(),
                ignored -> new Semaphore(Math.max(1, endpoint.getMaxConcurrency()), true));
        if (!endpointSemaphore.tryAcquire()) {
            tenant.release();
            return Permit.rejected("ENDPOINT_BULKHEAD");
        }
        return new Permit(true, null, () -> {
            endpointSemaphore.release();
            tenant.release();
        });
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

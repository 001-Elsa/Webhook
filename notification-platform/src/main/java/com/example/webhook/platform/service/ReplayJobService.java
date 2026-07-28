package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.ReplayJobRepository;
import com.example.webhook.platform.security.RequestContext;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

@Service
public class ReplayJobService {
    private final ReplayJobRepository jobs;
    private final DeliveryTaskRepository deliveries;
    private final DeliveryService deliveryService;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final boolean approvalRequired;

    public ReplayJobService(ReplayJobRepository jobs, DeliveryTaskRepository deliveries,
                            DeliveryService deliveryService, AuditService audit,
                            TransactionTemplate transactions, MeterRegistry metrics,
                            @Value("${webhook.replay.approval-required:true}") boolean approvalRequired) {
        this.jobs = jobs;
        this.deliveries = deliveries;
        this.deliveryService = deliveryService;
        this.audit = audit;
        this.transactions = transactions;
        this.metrics = metrics;
        this.approvalRequired = approvalRequired;
    }

    @Transactional
    public ReplayJob create(boolean dryRun, int maxDeliveries) {
        var principal = RequestContext.principal();
        ReplayJob job = new ReplayJob();
        job.setTenantId(principal.tenantId());
        job.setRequestedBy(principal.appId());
        job.setDryRun(dryRun);
        job.setMaxDeliveries(maxDeliveries);
        job.setFilterJson("{\"status\":\"DEAD\"}");
        boolean needsApproval = approvalRequired && !dryRun;
        job.setApprovalRequired(needsApproval);
        job.setStatus(needsApproval ? ReplayJobStatus.AWAITING_APPROVAL : ReplayJobStatus.PENDING);
        ReplayJob saved = jobs.save(job);
        audit.record("REPLAY_JOB_CREATED", "REPLAY_JOB", String.valueOf(saved.getId()), "SUCCESS",
                "{\"dryRun\":" + dryRun + ",\"maxDeliveries\":" + maxDeliveries + "}");
        return saved;
    }

    @Transactional
    public ReplayJob approve(Long id) {
        ReplayJob job = owned(id);
        if (job.getStatus() != ReplayJobStatus.AWAITING_APPROVAL) {
            throw new IllegalArgumentException("Replay job is not awaiting approval");
        }
        job.setApprovedBy(RequestContext.principal().appId());
        job.setApprovedAt(Instant.now());
        job.setStatus(ReplayJobStatus.PENDING);
        audit.record("REPLAY_JOB_APPROVED", "REPLAY_JOB", String.valueOf(id), "SUCCESS", null);
        return jobs.save(job);
    }

    @Transactional
    public ReplayJob cancel(Long id) {
        ReplayJob job = owned(id);
        if (job.getStatus() == ReplayJobStatus.COMPLETED || job.getStatus() == ReplayJobStatus.FAILED) return job;
        job.setCancellationRequested(true);
        if (job.getStatus() != ReplayJobStatus.RUNNING) {
            job.setStatus(ReplayJobStatus.CANCELLED);
            job.setCompletedAt(Instant.now());
        }
        audit.record("REPLAY_JOB_CANCELLED", "REPLAY_JOB", String.valueOf(id), "SUCCESS", null);
        return jobs.save(job);
    }

    public ReplayJob get(Long id) { return owned(id); }
    public List<ReplayJob> list() {
        return jobs.findTop100ByTenantIdOrderByCreatedAtDesc(RequestContext.principal().tenantId());
    }

    public void runNext() {
        List<ReplayJob> pending = jobs.findByStatusOrderByCreatedAtAsc(ReplayJobStatus.PENDING, PageRequest.of(0, 1));
        if (pending.isEmpty()) return;
        Long jobId = pending.get(0).getId();
        ReplayJob claimed = transactions.execute(status -> {
            ReplayJob job = jobs.findById(jobId).orElse(null);
            if (job == null || job.getStatus() != ReplayJobStatus.PENDING) return null;
            job.setStatus(ReplayJobStatus.RUNNING);
            job.setStartedAt(Instant.now());
            return jobs.save(job);
        });
        if (claimed == null) return;
        try {
            if (claimed.isDryRun()) runDryRun(claimed);
            else runReplay(claimed);
        } catch (RuntimeException failure) {
            transactions.executeWithoutResult(status -> jobs.findById(jobId).ifPresent(job -> {
                job.setStatus(ReplayJobStatus.FAILED);
                job.setLastError(truncate(failure.getMessage(), 1000));
                job.setCompletedAt(Instant.now());
                jobs.save(job);
            }));
            metrics.counter("webhook.replay.job.failed").increment();
        }
    }

    private void runDryRun(ReplayJob job) {
        List<DeliveryTask> candidates = deliveries.findByEventTenantIdAndStatusOrderByIdAsc(
                job.getTenantId(), DeliveryStatus.DEAD, PageRequest.of(0, job.getMaxDeliveries()));
        finish(job.getId(), candidates.size(), 0, candidates.size(), 0, ReplayJobStatus.COMPLETED);
    }

    private void runReplay(ReplayJob initial) {
        int processed = 0;
        int replayed = 0;
        int skipped = 0;
        int failed = 0;
        while (processed < initial.getMaxDeliveries()) {
            ReplayJob snapshot = jobs.findById(initial.getId()).orElseThrow();
            if (snapshot.isCancellationRequested()) {
                finish(initial.getId(), processed, replayed, skipped, failed, ReplayJobStatus.CANCELLED);
                return;
            }
            int batchSize = Math.min(100, initial.getMaxDeliveries() - processed);
            List<DeliveryTask> batch = deliveries.findByEventTenantIdAndStatusOrderByIdAsc(
                    initial.getTenantId(), DeliveryStatus.DEAD, PageRequest.of(0, batchSize));
            if (batch.isEmpty()) break;
            for (DeliveryTask task : batch) {
                try {
                    deliveryService.retryNow(task.getId(), initial.getTenantId());
                    replayed++;
                } catch (IllegalArgumentException alreadyChanged) {
                    skipped++;
                } catch (RuntimeException failure) {
                    failed++;
                }
                processed++;
            }
            updateProgress(initial.getId(), processed, replayed, skipped, failed);
        }
        finish(initial.getId(), processed, replayed, skipped, failed, ReplayJobStatus.COMPLETED);
    }

    private void updateProgress(Long id, int processed, int replayed, int skipped, int failed) {
        transactions.executeWithoutResult(status -> jobs.findById(id).ifPresent(job -> {
            job.setProcessedCount(processed);
            job.setReplayedCount(replayed);
            job.setSkippedCount(skipped);
            job.setFailedCount(failed);
            jobs.save(job);
        }));
    }

    private void finish(Long id, int processed, int replayed, int skipped, int failed, ReplayJobStatus status) {
        transactions.executeWithoutResult(tx -> jobs.findById(id).ifPresent(job -> {
            job.setProcessedCount(processed);
            job.setReplayedCount(replayed);
            job.setSkippedCount(skipped);
            job.setFailedCount(failed);
            job.setStatus(status);
            job.setCompletedAt(Instant.now());
            jobs.save(job);
        }));
        metrics.counter("webhook.replay.job.completed", "status", status.name()).increment();
    }

    private ReplayJob owned(Long id) {
        return jobs.findByIdAndTenantId(id, RequestContext.principal().tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Replay job not found: " + id));
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

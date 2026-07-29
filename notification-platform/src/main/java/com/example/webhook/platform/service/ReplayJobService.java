package com.example.webhook.platform.service;

import com.example.webhook.platform.api.dto.CreateReplayJobRequest;
import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.ReplayJobRepository;
import com.example.webhook.platform.security.RequestContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReplayJobService {
    private final ReplayJobRepository jobs;
    private final DeliveryTaskRepository deliveries;
    private final DeliveryService deliveryService;
    private final AuditService audit;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final ObjectMapper json;
    private final boolean approvalRequired;
    private final int maxPerSecond;
    private final int dryRunPreviewLimit;

    public ReplayJobService(ReplayJobRepository jobs, DeliveryTaskRepository deliveries,
                            DeliveryService deliveryService, AuditService audit,
                            TransactionTemplate transactions, MeterRegistry metrics, ObjectMapper json,
                            @Value("${webhook.replay.approval-required:true}") boolean approvalRequired,
                            @Value("${webhook.replay.max-per-second:20}") int maxPerSecond,
                            @Value("${webhook.replay.dry-run-preview-limit:50}") int dryRunPreviewLimit) {
        this.jobs = jobs;
        this.deliveries = deliveries;
        this.deliveryService = deliveryService;
        this.audit = audit;
        this.transactions = transactions;
        this.metrics = metrics;
        this.json = json;
        this.approvalRequired = approvalRequired;
        this.maxPerSecond = Math.max(1, maxPerSecond);
        this.dryRunPreviewLimit = Math.max(1, dryRunPreviewLimit);
    }

    @Transactional
    public ReplayJob create(CreateReplayJobRequest request) {
        var principal = RequestContext.principal();
        int maxDeliveries = request.maxDeliveries() == null ? 1000 : request.maxDeliveries();
        ReplayJob job = new ReplayJob();
        job.setTenantId(principal.tenantId());
        job.setRequestedBy(principal.appId());
        job.setDryRun(request.dryRun());
        job.setMaxDeliveries(maxDeliveries);
        job.setFilterJson(buildFilterJson(request));
        boolean needsApproval = approvalRequired && !request.dryRun();
        job.setApprovalRequired(needsApproval);
        job.setStatus(needsApproval ? ReplayJobStatus.AWAITING_APPROVAL : ReplayJobStatus.PENDING);
        ReplayJob saved = jobs.save(job);
        audit.record("REPLAY_JOB_CREATED", "REPLAY_JOB", String.valueOf(saved.getId()), "SUCCESS",
                "{\"dryRun\":" + request.dryRun() + ",\"maxDeliveries\":" + maxDeliveries + "}");
        return saved;
    }

    /** Compatibility for older call sites / tests. */
    @Transactional
    public ReplayJob create(boolean dryRun, int maxDeliveries) {
        return create(new CreateReplayJobRequest(dryRun, maxDeliveries, null, null, null, null, null));
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
        Instant startedAt = Instant.now();
        Integer claimed = transactions.execute(status ->
                jobs.claimPending(jobId, startedAt, ReplayJobStatus.RUNNING, ReplayJobStatus.PENDING));
        if (claimed == null || claimed != 1) return;
        ReplayJob claimedJob = jobs.findById(jobId).orElse(null);
        if (claimedJob == null) return;
        try {
            if (claimedJob.isDryRun()) runDryRun(claimedJob);
            else runReplay(claimedJob);
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
        ReplayFilters filters = parseFilters(job.getFilterJson());
        List<DeliveryTask> candidates = findCandidates(job.getTenantId(), filters, job.getMaxDeliveries());
        String summary = buildDryRunSummary(candidates);
        transactions.executeWithoutResult(tx -> jobs.findById(job.getId()).ifPresent(stored -> {
            stored.setProcessedCount(candidates.size());
            stored.setReplayedCount(0);
            stored.setSkippedCount(candidates.size());
            stored.setFailedCount(0);
            stored.setResultSummaryJson(summary);
            stored.setStatus(ReplayJobStatus.COMPLETED);
            stored.setCompletedAt(Instant.now());
            jobs.save(stored);
        }));
        metrics.counter("webhook.replay.job.completed", "status", ReplayJobStatus.COMPLETED.name()).increment();
    }

    private void runReplay(ReplayJob initial) {
        ReplayFilters filters = parseFilters(initial.getFilterJson());
        int processed = 0;
        int replayed = 0;
        int skipped = 0;
        int failed = 0;
        long intervalMs = Math.max(1L, 1000L / maxPerSecond);
        while (processed < initial.getMaxDeliveries()) {
            ReplayJob snapshot = jobs.findById(initial.getId()).orElseThrow();
            if (snapshot.isCancellationRequested()) {
                finish(initial.getId(), processed, replayed, skipped, failed, ReplayJobStatus.CANCELLED, null);
                return;
            }
            int batchSize = Math.min(100, initial.getMaxDeliveries() - processed);
            List<DeliveryTask> batch = findCandidates(initial.getTenantId(), filters, batchSize);
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
                throttle(intervalMs);
            }
            updateProgress(initial.getId(), processed, replayed, skipped, failed);
        }
        finish(initial.getId(), processed, replayed, skipped, failed, ReplayJobStatus.COMPLETED, null);
    }

    private void throttle(long intervalMs) {
        try {
            Thread.sleep(intervalMs);
            metrics.counter("webhook.replay.throttled").increment();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Replay throttle interrupted", interrupted);
        }
    }

    private List<DeliveryTask> findCandidates(String tenantId, ReplayFilters filters, int limit) {
        return deliveries.findReplayCandidates(
                tenantId, DeliveryStatus.DEAD,
                filters.endpointId(), filters.eventType(),
                filters.failedAfter(), filters.failedBefore(), filters.httpStatus(),
                PageRequest.of(0, limit));
    }

    private String buildDryRunSummary(List<DeliveryTask> candidates) {
        ObjectNode root = json.createObjectNode();
        ArrayNode preview = root.putArray("preview");
        int previewCount = Math.min(dryRunPreviewLimit, candidates.size());
        Map<String, Integer> byHttpStatus = new LinkedHashMap<>();
        Map<String, Integer> byErrorClass = new LinkedHashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            DeliveryTask task = candidates.get(i);
            String statusKey = task.getLastStatusCode() == null ? "none" : String.valueOf(task.getLastStatusCode());
            byHttpStatus.merge(statusKey, 1, Integer::sum);
            byErrorClass.merge(errorClass(task), 1, Integer::sum);
            if (i < previewCount) {
                ObjectNode row = preview.addObject();
                row.put("deliveryId", task.getId());
                row.put("endpointId", task.getEndpoint().getId());
                row.put("endpointName", task.getEndpoint().getName());
                if (task.getLastError() != null) row.put("lastError", truncate(task.getLastError(), 200));
                if (task.getLastStatusCode() != null) row.put("httpStatus", task.getLastStatusCode());
            }
        }
        root.put("matched", candidates.size());
        root.set("riskByHttpStatus", json.valueToTree(byHttpStatus));
        root.set("riskByErrorClass", json.valueToTree(byErrorClass));
        try {
            return json.writeValueAsString(root);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize replay dry-run summary", ex);
        }
    }

    private String errorClass(DeliveryTask task) {
        Integer code = task.getLastStatusCode();
        if (code != null) {
            if (code >= 500) return "HTTP_5XX";
            if (code >= 400) return "HTTP_4XX";
            return "HTTP_OTHER";
        }
        String error = task.getLastError() == null ? "" : task.getLastError().toLowerCase();
        if (error.contains("timeout")) return "TIMEOUT";
        if (error.contains("connect") || error.contains("connection")) return "CONNECTION";
        if (error.isBlank()) return "UNKNOWN";
        return "ERROR";
    }

    private String buildFilterJson(CreateReplayJobRequest request) {
        ObjectNode node = json.createObjectNode();
        node.put("status", "DEAD");
        if (request.endpointId() != null) node.put("endpointId", request.endpointId());
        if (request.eventType() != null && !request.eventType().isBlank()) node.put("eventType", request.eventType());
        if (request.failedAfter() != null) node.put("failedAfter", request.failedAfter().toString());
        if (request.failedBefore() != null) node.put("failedBefore", request.failedBefore().toString());
        if (request.httpStatus() != null) node.put("httpStatus", request.httpStatus());
        try {
            return json.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize replay filters", ex);
        }
    }

    private ReplayFilters parseFilters(String filterJson) {
        if (filterJson == null || filterJson.isBlank()) {
            return new ReplayFilters(null, null, null, null, null);
        }
        try {
            JsonNode node = json.readTree(filterJson);
            Long endpointId = node.hasNonNull("endpointId") ? node.get("endpointId").asLong() : null;
            String eventType = node.hasNonNull("eventType") ? node.get("eventType").asText() : null;
            Instant failedAfter = node.hasNonNull("failedAfter") ? Instant.parse(node.get("failedAfter").asText()) : null;
            Instant failedBefore = node.hasNonNull("failedBefore") ? Instant.parse(node.get("failedBefore").asText()) : null;
            Integer httpStatus = node.hasNonNull("httpStatus") ? node.get("httpStatus").asInt() : null;
            return new ReplayFilters(endpointId, eventType, failedAfter, failedBefore, httpStatus);
        } catch (RuntimeException | JsonProcessingException ex) {
            throw new IllegalStateException("Invalid replay filterJson", ex);
        }
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

    private void finish(Long id, int processed, int replayed, int skipped, int failed,
                        ReplayJobStatus status, String resultSummaryJson) {
        transactions.executeWithoutResult(tx -> jobs.findById(id).ifPresent(job -> {
            job.setProcessedCount(processed);
            job.setReplayedCount(replayed);
            job.setSkippedCount(skipped);
            job.setFailedCount(failed);
            if (resultSummaryJson != null) job.setResultSummaryJson(resultSummaryJson);
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

    private record ReplayFilters(Long endpointId, String eventType, Instant failedAfter,
                                 Instant failedBefore, Integer httpStatus) { }
}

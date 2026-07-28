package com.example.webhook.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "replay_jobs")
public class ReplayJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 80) private String tenantId;
    @Column(nullable = false, length = 80) private String requestedBy;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private ReplayJobStatus status = ReplayJobStatus.PENDING;
    @Column(nullable = false) private boolean dryRun;
    @Column(nullable = false) private int maxDeliveries;
    @Column(nullable = false) private int processedCount;
    @Column(nullable = false) private int replayedCount;
    @Column(nullable = false) private int skippedCount;
    @Column(nullable = false) private int failedCount;
    @Column(nullable = false) private boolean cancellationRequested;
    @Column(nullable = false) private boolean approvalRequired = true;
    @Column(length = 80) private String approvedBy;
    private Instant approvedAt;
    @Column(columnDefinition = "TEXT") private String filterJson;
    @Column(length = 1000) private String lastError;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();
    private Instant startedAt;
    private Instant completedAt;

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String requestedBy) { this.requestedBy = requestedBy; }
    public ReplayJobStatus getStatus() { return status; }
    public void setStatus(ReplayJobStatus status) { this.status = status; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public int getMaxDeliveries() { return maxDeliveries; }
    public void setMaxDeliveries(int maxDeliveries) { this.maxDeliveries = maxDeliveries; }
    public int getProcessedCount() { return processedCount; }
    public void setProcessedCount(int processedCount) { this.processedCount = processedCount; }
    public int getReplayedCount() { return replayedCount; }
    public void setReplayedCount(int replayedCount) { this.replayedCount = replayedCount; }
    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
    public boolean isCancellationRequested() { return cancellationRequested; }
    public void setCancellationRequested(boolean cancellationRequested) { this.cancellationRequested = cancellationRequested; }
    public boolean isApprovalRequired() { return approvalRequired; }
    public void setApprovalRequired(boolean approvalRequired) { this.approvalRequired = approvalRequired; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public String getFilterJson() { return filterJson; }
    public void setFilterJson(String filterJson) { this.filterJson = filterJson; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}

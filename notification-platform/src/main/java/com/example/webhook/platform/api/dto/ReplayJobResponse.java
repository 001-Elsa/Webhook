package com.example.webhook.platform.api.dto;

import com.example.webhook.platform.domain.ReplayJob;
import com.example.webhook.platform.domain.ReplayJobStatus;
import java.time.Instant;

public record ReplayJobResponse(
        Long id, ReplayJobStatus status, boolean dryRun, int maxDeliveries,
        int processed, int replayed, int skipped, int failed,
        boolean cancellationRequested, boolean approvalRequired,
        Instant createdAt, Instant startedAt, Instant completedAt, String lastError
) {
    public static ReplayJobResponse from(ReplayJob job) {
        return new ReplayJobResponse(job.getId(), job.getStatus(), job.isDryRun(), job.getMaxDeliveries(),
                job.getProcessedCount(), job.getReplayedCount(), job.getSkippedCount(), job.getFailedCount(),
                job.isCancellationRequested(), job.isApprovalRequired(), job.getCreatedAt(), job.getStartedAt(),
                job.getCompletedAt(), job.getLastError());
    }
}

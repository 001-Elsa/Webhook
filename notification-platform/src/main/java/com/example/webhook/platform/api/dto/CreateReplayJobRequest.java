package com.example.webhook.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.time.Instant;

public record CreateReplayJobRequest(
        boolean dryRun,
        @Min(1) @Max(10000) Integer maxDeliveries,
        Long endpointId,
        String eventType,
        Instant failedAfter,
        Instant failedBefore,
        Integer httpStatus
) { }

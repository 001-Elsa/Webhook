package com.example.webhook.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateTenantQuotaRequest(
        @Min(1) @Max(100000) int ingressPerSecond,
        @Min(1) long maxPendingDeliveries,
        @Min(1) @Max(10000) int maxConcurrentDeliveries,
        @Min(1) long dailyEventLimit,
        @Min(1024) long dailyPayloadBytes,
        @Min(1) @Max(100) int schedulingWeight,
        @Min(1) @Max(3650) Integer attemptRetentionDays
) { }

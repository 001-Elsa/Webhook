package com.example.webhook.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record CreateReplayJobRequest(
        boolean dryRun,
        @Min(1) @Max(10000) Integer maxDeliveries
) { }

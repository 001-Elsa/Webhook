package com.example.webhook.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record UpdateEndpointPolicyRequest(
        @Min(1) @Max(1000) int maxConcurrency,
        @Min(1) @Max(100) int failureThreshold,
        @Min(1) @Max(3600) int circuitCooldownSeconds
) { }

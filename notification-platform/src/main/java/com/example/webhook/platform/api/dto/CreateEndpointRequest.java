package com.example.webhook.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateEndpointRequest(
        @NotBlank String name,
        @NotBlank String url,
        @NotBlank String secret,
        String eventTypes,
        Boolean active,
        @Min(1) @Max(20) Integer maxAttempts,
        @Min(1) @Max(600) Integer rateLimitPerMinute
) {
}

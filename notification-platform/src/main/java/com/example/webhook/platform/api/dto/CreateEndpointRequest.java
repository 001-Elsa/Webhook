package com.example.webhook.platform.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateEndpointRequest(
        @NotBlank @Size(max = 80) String name,
        @NotBlank @Size(max = 500) String url,
        @NotBlank @Size(max = 256) String secret,
        @Size(max = 500) String eventTypes,
        Boolean active,
        @Min(1) @Max(20) Integer maxAttempts,
        @Min(1) @Max(600) Integer rateLimitPerMinute
) {
}

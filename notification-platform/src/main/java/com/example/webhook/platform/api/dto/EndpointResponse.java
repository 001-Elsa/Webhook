package com.example.webhook.platform.api.dto;

import com.example.webhook.platform.domain.WebhookEndpoint;
import java.time.Instant;

public record EndpointResponse(Long id, String name, String url, String eventTypes, String filterExpression,
                               boolean active, int maxAttempts, int rateLimitPerMinute, int maxConcurrency,
                               int failureThreshold, Instant circuitOpenUntil, Instant pausedAt, Instant createdAt) {
    public static EndpointResponse from(WebhookEndpoint endpoint) {
        return new EndpointResponse(endpoint.getId(), endpoint.getName(), endpoint.getUrl(), endpoint.getEventTypes(),
                endpoint.getFilterExpression(), endpoint.isActive(), endpoint.getMaxAttempts(),
                endpoint.getRateLimitPerMinute(), endpoint.getMaxConcurrency(), endpoint.getFailureThreshold(),
                endpoint.getCircuitOpenUntil(), endpoint.getPausedAt(), endpoint.getCreatedAt());
    }
}

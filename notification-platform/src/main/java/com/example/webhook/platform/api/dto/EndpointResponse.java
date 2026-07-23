package com.example.webhook.platform.api.dto;

import com.example.webhook.platform.domain.WebhookEndpoint;
import java.time.Instant;

public record EndpointResponse(Long id, String name, String url, String eventTypes, boolean active,
                               int maxAttempts, int rateLimitPerMinute, Instant createdAt) {
    public static EndpointResponse from(WebhookEndpoint endpoint) {
        return new EndpointResponse(endpoint.getId(), endpoint.getName(), endpoint.getUrl(), endpoint.getEventTypes(),
                endpoint.isActive(), endpoint.getMaxAttempts(), endpoint.getRateLimitPerMinute(), endpoint.getCreatedAt());
    }
}

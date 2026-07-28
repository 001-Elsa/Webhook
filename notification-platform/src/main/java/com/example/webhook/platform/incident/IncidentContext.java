package com.example.webhook.platform.incident;

import java.util.List;
import java.util.Map;

/**
 * Deliberately excludes event payload, endpoint URL credentials, HMAC secret,
 * API keys, response bodies, and raw stack traces.
 */
public record IncidentContext(
        Long deliveryId,
        String tenantId,
        String deliveryStatus,
        Integer lastStatusCode,
        int attemptCount,
        boolean endpointPaused,
        boolean circuitOpen,
        long outboxBacklog,
        List<Evidence> evidence,
        Map<String, Number> signals,
        String runbookExcerpt
) {
    public record Evidence(String id, String type, String value) { }
}

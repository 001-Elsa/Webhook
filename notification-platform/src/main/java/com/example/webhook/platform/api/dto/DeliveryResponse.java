package com.example.webhook.platform.api.dto;

import com.example.webhook.platform.domain.DeliveryStatus;
import com.example.webhook.platform.domain.DeliveryTask;
import java.time.Instant;

public record DeliveryResponse(Long id, String eventId, String eventType, Long endpointId, String endpointName,
                               String endpointUrl, DeliveryStatus status, int attemptCount, Instant nextAttemptAt,
                               String lastError, Integer lastStatusCode, Instant createdAt, Instant updatedAt) {
    public static DeliveryResponse from(DeliveryTask task) {
        return new DeliveryResponse(task.getId(), task.getEvent().getEventId(), task.getEvent().getType(),
                task.getEndpoint().getId(), task.getEndpoint().getName(), task.getEndpoint().getUrl(), task.getStatus(),
                task.getAttemptCount(), task.getNextAttemptAt(), task.getLastError(), task.getLastStatusCode(),
                task.getCreatedAt(), task.getUpdatedAt());
    }
}

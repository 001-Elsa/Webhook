package com.example.webhook.platform.api.dto;

import com.example.webhook.platform.domain.DeliveryAttempt;
import java.time.Instant;

public record DeliveryAttemptResponse(Long id, Long deliveryId, int attemptNo, boolean success, Integer statusCode,
                                      String responseBody, String errorMessage, long durationMs, Instant createdAt) {
    public static DeliveryAttemptResponse from(DeliveryAttempt attempt) {
        return new DeliveryAttemptResponse(attempt.getId(), attempt.getDelivery().getId(), attempt.getAttemptNo(),
                attempt.isSuccess(), attempt.getStatusCode(), attempt.getResponseBody(), attempt.getErrorMessage(),
                attempt.getDurationMs(), attempt.getCreatedAt());
    }
}

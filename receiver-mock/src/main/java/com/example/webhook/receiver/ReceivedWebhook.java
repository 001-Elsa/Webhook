package com.example.webhook.receiver;

import java.time.Instant;

public record ReceivedWebhook(
        Instant receivedAt,
        String merchant,
        String eventId,
        String eventType,
        String deliveryId,
        boolean signatureValid,
        String payload
) {
}

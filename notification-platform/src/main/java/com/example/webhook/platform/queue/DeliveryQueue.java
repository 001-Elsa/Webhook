package com.example.webhook.platform.queue;

public interface DeliveryQueue {
    void enqueue(Long deliveryId);
    void enqueueRetry(Long deliveryId, int attemptNo);
    void enqueueDead(Long deliveryId);
}

package com.example.webhook.platform.queue;

public interface DeliveryQueue {
    void enqueue(Long deliveryId);
    default void enqueue(Long deliveryId, String traceParent) { enqueue(deliveryId); }
    void enqueueDead(Long deliveryId);
}

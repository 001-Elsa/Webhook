package com.example.webhook.platform.queue;

public interface DeliveryQueue {
    void enqueue(Long deliveryId);
}

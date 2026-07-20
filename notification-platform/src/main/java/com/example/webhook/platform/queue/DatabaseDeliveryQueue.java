package com.example.webhook.platform.queue;

import org.springframework.stereotype.Component;

@Component
public class DatabaseDeliveryQueue implements DeliveryQueue {
    @Override
    public void enqueue(Long deliveryId) {
        // Database-backed queue: the scheduler scans due delivery rows.
        // RabbitMQ/Kafka/Redis Stream can replace this adapter without changing EventService.
    }
}

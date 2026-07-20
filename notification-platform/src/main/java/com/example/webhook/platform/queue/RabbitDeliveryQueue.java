package com.example.webhook.platform.queue;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class RabbitDeliveryQueue implements DeliveryQueue {
    private static final Logger log = LoggerFactory.getLogger(RabbitDeliveryQueue.class);
    private final RabbitTemplate rabbitTemplate;

    public RabbitDeliveryQueue(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        this.rabbitTemplate.setMandatory(true);
        this.rabbitTemplate.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) log.error("RabbitMQ rejected delivery message: {}", cause);
        });
        this.rabbitTemplate.setReturnsCallback(returned ->
                log.error("RabbitMQ returned unroutable message: exchange={}, key={}",
                        returned.getExchange(), returned.getRoutingKey()));
    }

    @Override
    public void enqueue(Long deliveryId) {
        rabbitTemplate.convertAndSend(RabbitTopology.DELIVERY_EXCHANGE, RabbitTopology.DELIVERY_KEY, deliveryId,
                message -> { message.getMessageProperties().setMessageId("delivery-" + deliveryId); return message; });
    }

    @Override
    public void enqueueRetry(Long deliveryId, int attemptNo) {
        String key = attemptNo <= 1 ? "retry.5s" : attemptNo <= 3 ? "retry.30s" : "retry.120s";
        rabbitTemplate.convertAndSend(RabbitTopology.RETRY_EXCHANGE, key, deliveryId);
    }

    @Override
    public void enqueueDead(Long deliveryId) {
        rabbitTemplate.convertAndSend(RabbitTopology.DEAD_EXCHANGE, RabbitTopology.DEAD_KEY, deliveryId);
    }
}

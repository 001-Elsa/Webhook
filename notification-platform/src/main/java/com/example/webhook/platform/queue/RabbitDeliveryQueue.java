package com.example.webhook.platform.queue;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import java.util.concurrent.TimeUnit;

@Component
public class RabbitDeliveryQueue implements DeliveryQueue {
    private static final long CONFIRM_TIMEOUT_MS = 5_000;
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
        CorrelationData correlation = correlation(deliveryId);
        rabbitTemplate.convertAndSend(RabbitTopology.DELIVERY_EXCHANGE, RabbitTopology.DELIVERY_KEY, deliveryId,
                message -> { message.getMessageProperties().setMessageId("delivery-" + deliveryId); return message; },
                correlation);
        awaitConfirmed(correlation);
    }

    @Override
    public void enqueueRetry(Long deliveryId, int attemptNo) {
        String key = attemptNo <= 1 ? "retry.5s" : attemptNo <= 3 ? "retry.30s" : "retry.120s";
        CorrelationData correlation = correlation(deliveryId);
        rabbitTemplate.convertAndSend(RabbitTopology.RETRY_EXCHANGE, key, deliveryId, correlation);
        awaitConfirmed(correlation);
    }

    @Override
    public void enqueueDead(Long deliveryId) {
        CorrelationData correlation = correlation(deliveryId);
        rabbitTemplate.convertAndSend(RabbitTopology.DEAD_EXCHANGE, RabbitTopology.DEAD_KEY, deliveryId, correlation);
        awaitConfirmed(correlation);
    }

    private CorrelationData correlation(Long deliveryId) {
        return new CorrelationData("delivery-" + deliveryId + "-" + java.util.UUID.randomUUID());
    }

    private void awaitConfirmed(CorrelationData correlation) {
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) throw new AmqpException("RabbitMQ rejected message: " + confirm.getReason());
            if (correlation.getReturned() != null) {
                throw new AmqpException("RabbitMQ returned unroutable message: "
                        + correlation.getReturned().getReplyText());
            }
        } catch (AmqpException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AmqpException("RabbitMQ publisher confirmation timed out or failed", ex);
        }
    }
}

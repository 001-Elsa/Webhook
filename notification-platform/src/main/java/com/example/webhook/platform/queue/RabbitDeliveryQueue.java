package com.example.webhook.platform.queue;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
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
        enqueue(deliveryId, null);
    }

    @Override
    public void enqueue(Long deliveryId, String traceParent) {
        withTraceParent(traceParent, () -> sendDelivery(deliveryId));
    }

    private void sendDelivery(Long deliveryId) {
        CorrelationData correlation = correlation(deliveryId);
        rabbitTemplate.convertAndSend(RabbitTopology.DELIVERY_EXCHANGE, RabbitTopology.DELIVERY_KEY, deliveryId,
                message -> { message.getMessageProperties().setMessageId("delivery-" + deliveryId); return message; },
                correlation);
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

    /**
     * Outbox publication runs after the HTTP request has ended. Re-attach its
     * persisted W3C parent so RabbitTemplate observation creates a publisher
     * span in the original trace and propagates it to the consumer.
     */
    private void withTraceParent(String traceParent, Runnable operation) {
        if (traceParent == null || !traceParent.matches("00-[0-9a-fA-F]{32}-[0-9a-fA-F]{16}-0[01]")) {
            operation.run();
            return;
        }
        String[] parts = traceParent.split("-");
        SpanContext parent = SpanContext.createFromRemoteParent(parts[1], parts[2], TraceFlags.getSampled(), TraceState.getDefault());
        try (Scope ignored = Context.root().with(Span.wrap(parent)).makeCurrent()) {
            operation.run();
        }
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

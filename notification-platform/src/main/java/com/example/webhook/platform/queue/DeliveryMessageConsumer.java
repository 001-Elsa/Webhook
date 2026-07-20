package com.example.webhook.platform.queue;

import com.example.webhook.platform.service.DeliveryService;
import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component
public class DeliveryMessageConsumer {
    private final DeliveryService deliveryService;
    private final DeliveryQueue deliveryQueue;

    public DeliveryMessageConsumer(DeliveryService deliveryService, DeliveryQueue deliveryQueue) {
        this.deliveryService = deliveryService;
        this.deliveryQueue = deliveryQueue;
    }

    @RabbitListener(queues = RabbitTopology.DELIVERY_QUEUE)
    public void consume(Long deliveryId, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            DeliveryService.Outcome outcome = deliveryService.processDelivery(deliveryId);
            if (outcome == DeliveryService.Outcome.RETRY) deliveryQueue.enqueueRetry(deliveryId, deliveryService.attemptCount(deliveryId));
            if (outcome == DeliveryService.Outcome.DEAD) deliveryQueue.enqueueDead(deliveryId);
            channel.basicAck(tag, false);
        } catch (Exception ex) {
            channel.basicNack(tag, false, true);
        }
    }
}

package com.example.webhook.platform.queue;

import com.example.webhook.platform.service.DeliveryService;
import com.rabbitmq.client.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import java.io.IOException;
import org.springframework.dao.DataAccessException;

@Component
public class DeliveryMessageConsumer {
    private static final Logger log = LoggerFactory.getLogger(DeliveryMessageConsumer.class);
    private final DeliveryService deliveryService;

    public DeliveryMessageConsumer(DeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    @RabbitListener(queues = RabbitTopology.DELIVERY_QUEUE)
    public void consume(Long deliveryId, Message message, Channel channel) throws IOException {
        long tag = message.getMessageProperties().getDeliveryTag();
        try {
            deliveryService.processDelivery(deliveryId);
            channel.basicAck(tag, false);
        } catch (DataAccessException ex) {
            log.warn("Database unavailable while processing delivery {}; requeueing", deliveryId, ex);
            channel.basicNack(tag, false, true);
        } catch (Exception ex) {
            log.error("Unexpected delivery consumer failure; routing delivery {} to dead queue", deliveryId, ex);
            try {
                deliveryService.markUnexpectedFailureDead(deliveryId, ex.getMessage());
                channel.basicAck(tag, false);
            } catch (Exception persistenceFailure) {
                log.error("Could not persist terminal state for delivery {}; requeueing source message", deliveryId, persistenceFailure);
                channel.basicNack(tag, false, true);
            }
        }
    }
}

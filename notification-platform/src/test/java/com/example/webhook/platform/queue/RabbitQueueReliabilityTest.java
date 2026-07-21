package com.example.webhook.platform.queue;

import com.example.webhook.platform.service.DeliveryService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.Mockito.*;

class RabbitQueueReliabilityTest {
    @Test
    void retryPublishUsesConfiguredTtlRoutes() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RabbitDeliveryQueue queue = new RabbitDeliveryQueue(rabbitTemplate);

        queue.enqueueRetry(101L, 1);
        queue.enqueueRetry(102L, 2);
        queue.enqueueRetry(103L, 4);

        verify(rabbitTemplate).convertAndSend(RabbitTopology.RETRY_EXCHANGE, "retry.5s", 101L);
        verify(rabbitTemplate).convertAndSend(RabbitTopology.RETRY_EXCHANGE, "retry.30s", 102L);
        verify(rabbitTemplate).convertAndSend(RabbitTopology.RETRY_EXCHANGE, "retry.120s", 103L);
    }

    @Test
    void unexpectedConsumerFailureGoesToDeadQueueWithoutRequeueLoop() throws Exception {
        DeliveryService deliveryService = mock(DeliveryService.class);
        DeliveryQueue deliveryQueue = mock(DeliveryQueue.class);
        DeliveryMessageConsumer consumer = new DeliveryMessageConsumer(deliveryService, deliveryQueue);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        Message message = new Message(new byte[0], properties);
        Channel channel = mock(Channel.class);
        when(deliveryService.processDelivery(201L)).thenThrow(new IllegalStateException("corrupt task"));

        consumer.consume(201L, message, channel);

        verify(deliveryQueue).enqueueDead(201L);
        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(7L, false, true);
    }
}

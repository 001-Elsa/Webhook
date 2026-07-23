package com.example.webhook.platform.queue;

import com.example.webhook.platform.service.DeliveryService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;

import static org.mockito.Mockito.*;
import org.springframework.dao.DataAccessResourceFailureException;

class RabbitQueueReliabilityTest {
    @Test
    void retryPublishUsesConfiguredTtlRoutes() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Long.class), any(CorrelationData.class));
        RabbitDeliveryQueue queue = new RabbitDeliveryQueue(rabbitTemplate);

        queue.enqueueRetry(101L, 1);
        queue.enqueueRetry(102L, 2);
        queue.enqueueRetry(103L, 4);

        verify(rabbitTemplate).convertAndSend(eq(RabbitTopology.RETRY_EXCHANGE), eq("retry.5s"), eq(101L), any(CorrelationData.class));
        verify(rabbitTemplate).convertAndSend(eq(RabbitTopology.RETRY_EXCHANGE), eq("retry.30s"), eq(102L), any(CorrelationData.class));
        verify(rabbitTemplate).convertAndSend(eq(RabbitTopology.RETRY_EXCHANGE), eq("retry.120s"), eq(103L), any(CorrelationData.class));
    }

    @Test
    void unexpectedConsumerFailureGoesToDeadQueueWithoutRequeueLoop() throws Exception {
        DeliveryService deliveryService = mock(DeliveryService.class);
        DeliveryMessageConsumer consumer = new DeliveryMessageConsumer(deliveryService);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        Message message = new Message(new byte[0], properties);
        Channel channel = mock(Channel.class);
        when(deliveryService.processDelivery(201L)).thenThrow(new IllegalStateException("corrupt task"));

        consumer.consume(201L, message, channel);

        verify(deliveryService).markUnexpectedFailureDead(201L, "corrupt task");
        verify(channel).basicAck(7L, false);
        verify(channel, never()).basicNack(7L, false, true);
    }

    @Test
    void terminalStatePersistenceFailureRequeuesSourceMessage() throws Exception {
        DeliveryService deliveryService = mock(DeliveryService.class);
        DeliveryMessageConsumer consumer = new DeliveryMessageConsumer(deliveryService);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(8L);
        Message message = new Message(new byte[0], properties);
        Channel channel = mock(Channel.class);
        when(deliveryService.processDelivery(202L)).thenThrow(new IllegalStateException("corrupt task"));
        doThrow(new IllegalStateException("database down")).when(deliveryService)
                .markUnexpectedFailureDead(202L, "corrupt task");

        consumer.consume(202L, message, channel);

        verify(channel).basicNack(8L, false, true);
        verify(channel, never()).basicAck(8L, false);
        verify(deliveryService).markUnexpectedFailureDead(202L, "corrupt task");
    }

    @Test
    void transientDatabaseFailureIsRequeuedWithoutBeingMarkedDead() throws Exception {
        DeliveryService deliveryService = mock(DeliveryService.class);
        DeliveryMessageConsumer consumer = new DeliveryMessageConsumer(deliveryService);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(9L);
        Channel channel = mock(Channel.class);
        when(deliveryService.processDelivery(203L))
                .thenThrow(new DataAccessResourceFailureException("mysql down"));

        consumer.consume(203L, new Message(new byte[0], properties), channel);

        verify(channel).basicNack(9L, false, true);
        verify(deliveryService, never()).markUnexpectedFailureDead(anyLong(), anyString());
    }
}

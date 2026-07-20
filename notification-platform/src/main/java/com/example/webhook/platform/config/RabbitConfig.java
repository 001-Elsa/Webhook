package com.example.webhook.platform.config;

import com.example.webhook.platform.queue.RabbitTopology;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    @Bean DirectExchange deliveryExchange() { return new DirectExchange(RabbitTopology.DELIVERY_EXCHANGE, true, false); }
    @Bean DirectExchange retryExchange() { return new DirectExchange(RabbitTopology.RETRY_EXCHANGE, true, false); }
    @Bean DirectExchange deadExchange() { return new DirectExchange(RabbitTopology.DEAD_EXCHANGE, true, false); }

    @Bean Queue deliveryQueue() { return QueueBuilder.durable(RabbitTopology.DELIVERY_QUEUE).build(); }
    @Bean Binding deliveryBinding(Queue deliveryQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(deliveryQueue).to(deliveryExchange).with(RabbitTopology.DELIVERY_KEY);
    }

    @Bean Queue retry5sQueue() { return retryQueue(RabbitTopology.RETRY_5S_QUEUE, 5_000); }
    @Bean Queue retry30sQueue() { return retryQueue(RabbitTopology.RETRY_30S_QUEUE, 30_000); }
    @Bean Queue retry120sQueue() { return retryQueue(RabbitTopology.RETRY_120S_QUEUE, 120_000); }
    @Bean Binding retry5sBinding(Queue retry5sQueue, DirectExchange retryExchange) {
        return BindingBuilder.bind(retry5sQueue).to(retryExchange).with("retry.5s");
    }
    @Bean Binding retry30sBinding(Queue retry30sQueue, DirectExchange retryExchange) {
        return BindingBuilder.bind(retry30sQueue).to(retryExchange).with("retry.30s");
    }
    @Bean Binding retry120sBinding(Queue retry120sQueue, DirectExchange retryExchange) {
        return BindingBuilder.bind(retry120sQueue).to(retryExchange).with("retry.120s");
    }

    @Bean Queue deadQueue() { return QueueBuilder.durable(RabbitTopology.DEAD_QUEUE).build(); }
    @Bean Binding deadBinding(Queue deadQueue, DirectExchange deadExchange) {
        return BindingBuilder.bind(deadQueue).to(deadExchange).with(RabbitTopology.DEAD_KEY);
    }

    private Queue retryQueue(String name, int ttlMs) {
        return QueueBuilder.durable(name)
                .ttl(ttlMs)
                .deadLetterExchange(RabbitTopology.DELIVERY_EXCHANGE)
                .deadLetterRoutingKey(RabbitTopology.DELIVERY_KEY)
                .build();
    }
}

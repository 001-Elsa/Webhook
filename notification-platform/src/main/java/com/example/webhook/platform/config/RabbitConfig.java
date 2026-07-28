package com.example.webhook.platform.config;

import com.example.webhook.platform.queue.RabbitTopology;
import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    @Bean DirectExchange deliveryExchange() { return new DirectExchange(RabbitTopology.DELIVERY_EXCHANGE, true, false); }
    @Bean DirectExchange deadExchange() { return new DirectExchange(RabbitTopology.DEAD_EXCHANGE, true, false); }

    @Bean Queue deliveryQueue() { return QueueBuilder.durable(RabbitTopology.DELIVERY_QUEUE).build(); }
    @Bean Binding deliveryBinding(Queue deliveryQueue, DirectExchange deliveryExchange) {
        return BindingBuilder.bind(deliveryQueue).to(deliveryExchange).with(RabbitTopology.DELIVERY_KEY);
    }


    @Bean Queue deadQueue() { return QueueBuilder.durable(RabbitTopology.DEAD_QUEUE).build(); }
    @Bean Binding deadBinding(Queue deadQueue, DirectExchange deadExchange) {
        return BindingBuilder.bind(deadQueue).to(deadExchange).with(RabbitTopology.DEAD_KEY);
    }

}

package com.example.webhook.platform.queue;

public final class RabbitTopology {
    public static final String DELIVERY_EXCHANGE = "eventrelay.delivery.exchange";
    public static final String DELIVERY_QUEUE = "eventrelay.delivery.queue";
    public static final String DELIVERY_KEY = "delivery";
    public static final String DEAD_EXCHANGE = "eventrelay.dead.exchange";
    public static final String DEAD_QUEUE = "eventrelay.dead.queue";
    public static final String DEAD_KEY = "dead";

    private RabbitTopology() { }
}

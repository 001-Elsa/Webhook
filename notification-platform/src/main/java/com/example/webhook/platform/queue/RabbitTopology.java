package com.example.webhook.platform.queue;

public final class RabbitTopology {
    public static final String DELIVERY_EXCHANGE = "eventrelay.delivery.exchange";
    public static final String DELIVERY_QUEUE = "eventrelay.delivery.queue";
    public static final String DELIVERY_KEY = "delivery";
    public static final String RETRY_EXCHANGE = "eventrelay.retry.exchange";
    public static final String RETRY_5S_QUEUE = "eventrelay.retry.5s.queue";
    public static final String RETRY_30S_QUEUE = "eventrelay.retry.30s.queue";
    public static final String RETRY_120S_QUEUE = "eventrelay.retry.120s.queue";
    public static final String DEAD_EXCHANGE = "eventrelay.dead.exchange";
    public static final String DEAD_QUEUE = "eventrelay.dead.queue";
    public static final String DEAD_KEY = "dead";

    private RabbitTopology() { }
}

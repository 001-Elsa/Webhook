package com.example.webhook.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OrderService {
    private final Map<String, OrderRecord> orders = new ConcurrentHashMap<>();
    private final RestClient platformClient;
    private final String appId;
    private final String apiKey;

    public OrderService(RestClient.Builder builder, @Value("${notification.platform-url}") String platformUrl,
                        @Value("${notification.app-id}") String appId,
                        @Value("${notification.api-key}") String apiKey) {
        this.platformClient = builder.baseUrl(platformUrl).build();
        this.appId = appId;
        this.apiKey = apiKey;
    }

    public OrderRecord create(BigDecimal amount) {
        String orderId = String.valueOf(System.currentTimeMillis());
        OrderRecord order = new OrderRecord(orderId, amount, OrderStatus.CREATED, Instant.now());
        orders.put(orderId, order);
        publish("order.created", order);
        return order;
    }

    public OrderRecord transition(String orderId, OrderStatus status) {
        OrderRecord current = orders.get(orderId);
        if (current == null) {
            throw new IllegalArgumentException("Order not found: " + orderId);
        }
        OrderRecord updated = new OrderRecord(orderId, current.amount(), status, Instant.now());
        orders.put(orderId, updated);
        publish("order." + status.name().toLowerCase(), updated);
        return updated;
    }

    public Map<String, OrderRecord> list() {
        return orders;
    }

    private void publish(String type, OrderRecord order) {
        platformClient.post()
                    .uri("/api/events")
                    .header("X-App-Id", appId)
                    .header("X-Api-Key", apiKey)
                    .header("X-Trace-Id", "order-" + order.orderId())
                    .body(Map.of(
                        "eventId", type + ":" + order.orderId() + ":" + UUID.randomUUID(),
                        "type", type,
                        "data", Map.of(
                                "orderId", order.orderId(),
                                "amount", order.amount(),
                                "status", order.status().name(),
                                "updatedAt", order.updatedAt().toString()
                        )
                ))
                .retrieve()
                .toBodilessEntity();
    }
}

package com.example.webhook.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderRecord(String orderId, BigDecimal amount, OrderStatus status, Instant updatedAt) {
}

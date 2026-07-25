package com.example.webhook.platform.repo;

/** Aggregate counts for one event, returned by a single database query. */
public record DeliveryStatusCounts(long pending, long succeeded, long dead) {
}

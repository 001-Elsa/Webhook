package com.example.webhook.platform.service;

import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryRateLimiter implements RateLimiter {
    private final Map<Long, Deque<Long>> buckets = new ConcurrentHashMap<>();

    @Override
    public synchronized boolean tryAcquire(Long endpointId, int limitPerMinute) {
        long now = Instant.now().toEpochMilli();
        long windowStart = now - 60_000;
        Deque<Long> timestamps = buckets.computeIfAbsent(endpointId, ignored -> new ArrayDeque<>());
        while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
            timestamps.removeFirst();
        }
        if (timestamps.size() >= limitPerMinute) {
            return false;
        }
        timestamps.addLast(now);
        return true;
    }
}

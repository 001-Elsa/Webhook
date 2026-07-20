package com.example.webhook.platform.service;

public interface RateLimiter {
    boolean tryAcquire(Long endpointId, int limitPerMinute);
}

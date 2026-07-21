package com.example.webhook.platform.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class RedisRateLimiter implements RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
            if current > tonumber(ARGV[1]) then return 0 end
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public boolean tryAcquire(Long endpointId, int limitPerMinute) {
        long window = System.currentTimeMillis() / 60_000;
        try {
            Long allowed = redis.execute(SCRIPT, List.of("eventrelay:rate:" + endpointId + ":" + window),
                    String.valueOf(limitPerMinute), "65000");
            return Long.valueOf(1L).equals(allowed);
        } catch (DataAccessException ex) {
            log.warn("Redis rate limiter unavailable; failing open for endpoint {}", endpointId);
            return true;
        }
    }
}

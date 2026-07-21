package com.example.webhook.platform.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.List;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class EventIdempotencyStore {
    private static final Logger log = LoggerFactory.getLogger(EventIdempotencyStore.class);
    private static final long TTL_SECONDS = Duration.ofHours(24).toSeconds();
    private static final DefaultRedisScript<Long> REMEMBER_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('SETNX', KEYS[1], '1') == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
                return 1
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public EventIdempotencyStore(StringRedisTemplate redis) { this.redis = redis; }

    public boolean isKnownDuplicate(String tenantId, String eventId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(tenantId, eventId)));
        } catch (DataAccessException ex) {
            log.warn("Could not read idempotency key; MySQL unique key remains authoritative");
            return false;
        }
    }

    public void remember(String tenantId, String eventId) {
        try {
            redis.execute(REMEMBER_SCRIPT, List.of(key(tenantId, eventId)), String.valueOf(TTL_SECONDS));
        } catch (DataAccessException ex) {
            log.warn("Could not cache idempotency key; MySQL unique key remains authoritative");
        }
    }

    private String key(String tenantId, String eventId) {
        return "eventrelay:idempotency:" + tenantId + ":" + eventId;
    }
}

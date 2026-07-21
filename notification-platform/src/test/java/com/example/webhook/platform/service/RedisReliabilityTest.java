package com.example.webhook.platform.service;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisReliabilityTest {
    @Test
    void rateLimiterFailsOpenWhenRedisIsUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), any(List.class), anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        RedisRateLimiter limiter = new RedisRateLimiter(redis);

        assertThat(limiter.tryAcquire(10L, 1)).isTrue();
    }
}

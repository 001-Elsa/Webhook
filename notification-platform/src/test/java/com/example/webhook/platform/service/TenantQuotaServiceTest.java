package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.TenantQuota;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.TenantQuotaRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TenantQuotaServiceTest {
    @Mock TenantQuotaRepository quotas;
    @Mock DeliveryTaskRepository deliveries;
    @Mock StringRedisTemplate redis;
    @Mock JdbcTemplate jdbc;
    private SimpleMeterRegistry metrics;
    private TenantQuotaService service;

    @BeforeEach
    void setUp() {
        metrics = new SimpleMeterRegistry();
        service = new TenantQuotaService(quotas, deliveries, redis, jdbc, metrics);
    }

    @Test
    void ingressRateFailsOpenWhenRedisUnavailable() {
        TenantQuota quota = quota("t1", 10, 1_000_000L);
        when(quotas.findById("t1")).thenReturn(Optional.of(quota));
        when(redis.execute(any(RedisScript.class), any(List.class), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        when(deliveries.countByEventTenantIdAndStatusIn(eq("t1"), any())).thenReturn(0L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("accepted_events", 1L, "payload_bytes", 10L));

        service.reserveIngress("t1", 1, 10);

        assertThat(metrics.counter("webhook.quota.degraded", "dependency", "redis").count()).isEqualTo(1.0);
    }

    @Test
    void rejectsDailyPayloadBytesWhenIngressTrafficExceedsQuota() {
        TenantQuota quota = quota("t1", 100, 100L);
        when(quotas.findById("t1")).thenReturn(Optional.of(quota));
        when(redis.execute(any(RedisScript.class), any(List.class), any(), any(), any(), any())).thenReturn(1L);
        when(deliveries.countByEventTenantIdAndStatusIn(eq("t1"), any())).thenReturn(0L);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.queryForMap(anyString(), any(Object[].class)))
                .thenReturn(Map.of("accepted_events", 1L, "payload_bytes", 101L));

        assertThatThrownBy(() -> service.reserveIngress("t1", 1, 50))
                .isInstanceOf(TenantQuotaService.QuotaExceededException.class)
                .hasMessageContaining("daily_payload_bytes");
        assertThat(metrics.counter("webhook.quota.rejected", "reason", "daily_payload_bytes").count())
                .isEqualTo(1.0);
    }

    @Test
    void rejectsWhenTokenBucketDeniesIngress() {
        TenantQuota quota = quota("t1", 5, 1_000_000L);
        when(quotas.findById("t1")).thenReturn(Optional.of(quota));
        when(redis.execute(any(RedisScript.class), any(List.class), any(), any(), any(), any())).thenReturn(0L);

        assertThatThrownBy(() -> service.reserveIngress("t1", 1, 10))
                .isInstanceOf(TenantQuotaService.QuotaExceededException.class)
                .hasMessageContaining("ingress_rate");
    }

    @Test
    void concurrencyLimitUsesShortLivedCacheAndInvalidates() {
        TenantQuota quota = quota("t1", 100, 1_000_000L);
        quota.setMaxConcurrentDeliveries(4);
        when(quotas.findById("t1")).thenReturn(Optional.of(quota));

        assertThat(service.concurrencyLimit("t1")).isEqualTo(4);
        assertThat(service.concurrencyLimit("t1")).isEqualTo(4);
        verify(quotas, times(1)).findById("t1");

        service.invalidateCache("t1");
        quota.setMaxConcurrentDeliveries(8);
        assertThat(service.concurrencyLimit("t1")).isEqualTo(8);
        verify(quotas, times(2)).findById("t1");
    }

    @Test
    void schedulingWeightDefaultsToOne() {
        when(quotas.findById("missing")).thenReturn(Optional.empty());
        assertThat(service.schedulingWeight("missing")).isEqualTo(1);
    }

    private static TenantQuota quota(String tenantId, int ingressPerSecond, long dailyPayloadBytes) {
        TenantQuota quota = new TenantQuota();
        quota.setTenantId(tenantId);
        quota.setIngressPerSecond(ingressPerSecond);
        quota.setDailyPayloadBytes(dailyPayloadBytes);
        return quota;
    }
}

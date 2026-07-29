package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.CircuitState;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EndpointGuardTest {
    @Mock TenantQuotaService quotas;
    @Mock WebhookEndpointRepository endpoints;
    @Mock StringRedisTemplate redis;
    private SimpleMeterRegistry metrics;
    private EndpointGuard guard;

    @BeforeEach
    void setUp() {
        metrics = new SimpleMeterRegistry();
        guard = new EndpointGuard(quotas, endpoints, redis, metrics, false);
        lenient().when(quotas.concurrencyLimit(anyString())).thenReturn(2);
    }

    @Test
    void rejectsPausedEndpoint() {
        WebhookEndpoint endpoint = endpoint(1L, CircuitState.CLOSED);
        endpoint.setActive(false);
        endpoint.setPausedAt(Instant.now());

        EndpointGuard.Permit permit = guard.tryAcquire("t1", endpoint);

        assertThat(permit.acquired()).isFalse();
        assertThat(permit.rejectionReason()).isEqualTo("PAUSED");
    }

    @Test
    void rejectsWhileCircuitOpenBeforeCooldownExpires() {
        WebhookEndpoint endpoint = endpoint(2L, CircuitState.OPEN);
        endpoint.setCircuitOpenUntil(Instant.now().plusSeconds(60));

        EndpointGuard.Permit permit = guard.tryAcquire("t1", endpoint);

        assertThat(permit.acquired()).isFalse();
        assertThat(permit.rejectionReason()).isEqualTo("CIRCUIT_OPEN");
    }

    @Test
    void transitionsOpenToHalfOpenAfterCooldownAndAllowsProbe() {
        WebhookEndpoint endpoint = endpoint(3L, CircuitState.OPEN);
        endpoint.setCircuitOpenUntil(Instant.now().minusSeconds(1));
        endpoint.setHalfOpenMaxProbes(1);
        when(endpoints.findById(3L)).thenReturn(Optional.of(endpoint));
        when(endpoints.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EndpointGuard.Permit permit = guard.tryAcquire("t1", endpoint);

        assertThat(permit.acquired()).isTrue();
        assertThat(endpoint.getCircuitState()).isEqualTo(CircuitState.HALF_OPEN);
        assertThat(endpoint.getHalfOpenProbes()).isEqualTo(1);
        assertThat(metrics.counter("webhook.endpoint.circuit.state", "state", "HALF_OPEN").count())
                .isEqualTo(1.0);
        permit.close();
    }

    @Test
    void rejectsAdditionalHalfOpenProbesBeyondMax() {
        WebhookEndpoint endpoint = endpoint(4L, CircuitState.HALF_OPEN);
        endpoint.setHalfOpenMaxProbes(1);
        endpoint.setHalfOpenProbes(1);
        when(endpoints.findById(4L)).thenReturn(Optional.of(endpoint));

        EndpointGuard.Permit permit = guard.tryAcquire("t1", endpoint);

        assertThat(permit.acquired()).isFalse();
        assertThat(permit.rejectionReason()).isEqualTo("CIRCUIT_OPEN");
    }

    @Test
    void refreshesTenantSemaphoreWhenIdleAndLimitChanges() {
        WebhookEndpoint endpoint = endpoint(5L, CircuitState.CLOSED);
        when(quotas.concurrencyLimit("t1")).thenReturn(1);

        EndpointGuard.Permit first = guard.tryAcquire("t1", endpoint);
        assertThat(first.acquired()).isTrue();
        EndpointGuard.Permit blocked = guard.tryAcquire("t1", endpoint);
        assertThat(blocked.acquired()).isFalse();
        first.close();

        when(quotas.concurrencyLimit("t1")).thenReturn(2);
        EndpointGuard.Permit a = guard.tryAcquire("t1", endpoint);
        EndpointGuard.Permit b = guard.tryAcquire("t1", endpoint);
        assertThat(a.acquired()).isTrue();
        assertThat(b.acquired()).isTrue();
        a.close();
        b.close();
    }

    @Test
    void globalTenantBulkheadFailsOpenWhenRedisDown() {
        EndpointGuard globalGuard = new EndpointGuard(quotas, endpoints, redis, metrics, true);
        WebhookEndpoint endpoint = endpoint(6L, CircuitState.CLOSED);
        when(redis.execute(any(RedisScript.class), any(List.class), anyString()))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        EndpointGuard.Permit permit = globalGuard.tryAcquire("t1", endpoint);

        assertThat(permit.acquired()).isTrue();
        assertThat(metrics.counter("webhook.tenant.bulkhead.degraded", "dependency", "redis").count())
                .isEqualTo(1.0);
        permit.close();
    }

    private static WebhookEndpoint endpoint(Long id, CircuitState state) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        ReflectionTestUtils.setField(endpoint, "id", id);
        endpoint.setActive(true);
        endpoint.setMaxConcurrency(8);
        endpoint.setCircuitState(state);
        endpoint.setHalfOpenMaxProbes(1);
        return endpoint;
    }
}

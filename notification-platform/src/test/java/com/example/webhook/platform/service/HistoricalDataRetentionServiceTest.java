package com.example.webhook.platform.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HistoricalDataRetentionServiceTest {
    @Mock JdbcTemplate jdbc;

    @Test
    void disabledByDefaultDoesNotDelete() {
        HistoricalDataRetentionService service = new HistoricalDataRetentionService(
                jdbc, new SimpleMeterRegistry(), false, 90, 500);
        service.purgeCompletedEvents();
        verifyNoInteractions(jdbc);
    }

    @Test
    void enabledPurgesCompletedEventsInBatches() {
        when(jdbc.update(anyString(), any(), any(), anyInt())).thenReturn(3);
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        HistoricalDataRetentionService service = new HistoricalDataRetentionService(
                jdbc, metrics, true, 90, 500);
        service.purgeCompletedEvents();
        verify(jdbc, times(1)).update(contains("event_records"), eq("COMPLETED"), any(), eq(500));
        org.assertj.core.api.Assertions.assertThat(metrics.counter("webhook.events.purged").count()).isEqualTo(3.0);
    }
}

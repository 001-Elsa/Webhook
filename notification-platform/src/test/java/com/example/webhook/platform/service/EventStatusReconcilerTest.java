package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.EventRecordRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EventStatusReconcilerTest {
    @Test
    void repairsDispatchingEventAfterConcurrentDeliveriesHaveCommitted() {
        EventRecordRepository events = mock(EventRecordRepository.class);
        DeliveryTaskRepository deliveries = mock(DeliveryTaskRepository.class);
        EventRecord event = new EventRecord();
        ReflectionTestUtils.setField(event, "id", 10L);
        event.setStatus(EventStatus.DISPATCHING);
        when(events.findByStatusInOrderByCreatedAtAsc(any(), any(Pageable.class))).thenReturn(List.of(event));
        when(deliveries.countByEventIdAndStatus(10L, DeliveryStatus.SUCCEEDED)).thenReturn(1L);
        when(deliveries.countByEventIdAndStatus(10L, DeliveryStatus.DEAD)).thenReturn(1L);

        new EventStatusReconciler(events, deliveries, new EventStateMachine(),
                new SimpleMeterRegistry(), 100).reconcile();

        assertThat(event.getStatus()).isEqualTo(EventStatus.PARTIALLY_FAILED);
        verify(events).save(event);
    }
}

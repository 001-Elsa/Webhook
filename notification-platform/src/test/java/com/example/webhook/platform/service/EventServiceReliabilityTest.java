package com.example.webhook.platform.service;

import com.example.webhook.platform.api.dto.SubmitEventRequest;
import com.example.webhook.platform.domain.ClientRole;
import com.example.webhook.platform.domain.DeliveryTask;
import com.example.webhook.platform.domain.EventRecord;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.queue.DeliveryQueue;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.EventRecordRepository;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import com.example.webhook.platform.security.ApiPrincipal;
import com.example.webhook.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EventServiceReliabilityTest {
    @Mock EventRecordRepository eventRepository;
    @Mock WebhookEndpointRepository endpointRepository;
    @Mock DeliveryTaskRepository deliveryRepository;
    @Mock EndpointMatcher matcher;
    @Mock OutboxService outboxService;
    @Mock EventIdempotencyStore idempotencyStore;

    @AfterEach
    void clearContext() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        RequestContext.clear();
    }

    @Test
    void duplicateEventCountsOnlyCurrentTenantTasks() {
        RequestContext.set(new ApiPrincipal("tenant-a", "app-a", ClientRole.ADMIN), "trace-a");
        EventRecord duplicate = new EventRecord();
        duplicate.setTenantId("tenant-a");
        duplicate.setEventId("evt-1");
        when(eventRepository.findByTenantIdAndEventId("tenant-a", "evt-1")).thenReturn(Optional.of(duplicate));
        when(deliveryRepository.countByEventTenantIdAndEventEventId("tenant-a", "evt-1")).thenReturn(2L);

        var service = service();
        var response = service.submit(new SubmitEventRequest("evt-1", "order.created", Map.of("id", 1)));

        assertThat(response.duplicate()).isTrue();
        assertThat(response.deliveryTasks()).isEqualTo(2L);
        verify(deliveryRepository).countByEventTenantIdAndEventEventId("tenant-a", "evt-1");
    }

    @Test
    void newEventAndOutboxMessageAreStoredInTheSameTransaction() {
        RequestContext.set(new ApiPrincipal("tenant-a", "app-a", ClientRole.ADMIN), "trace-a");
        WebhookEndpoint endpoint = endpoint();
        when(eventRepository.findByTenantIdAndEventId("tenant-a", "evt-2")).thenReturn(Optional.empty());
        when(endpointRepository.findByTenantIdAndActiveTrue("tenant-a")).thenReturn(List.of(endpoint));
        when(matcher.supports(endpoint, "order.created")).thenReturn(true);
        when(deliveryRepository.save(any(DeliveryTask.class))).thenAnswer(invocation -> {
            DeliveryTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 42L);
            return task;
        });

        TransactionSynchronizationManager.initSynchronization();
        var service = service();
        var response = service.submit(new SubmitEventRequest("evt-2", "order.created", Map.of("id", 2)));

        assertThat(response.duplicate()).isFalse();
        verify(outboxService).add(42L, com.example.webhook.platform.domain.OutboxMessageType.DELIVERY, 0);
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        verify(idempotencyStore).remember("tenant-a", "evt-2");
    }

    private EventService service() {
        return new EventService(new ObjectMapper(), eventRepository, endpointRepository, deliveryRepository,
                matcher, outboxService, idempotencyStore, new EventStateMachine());
    }

    private WebhookEndpoint endpoint() {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        ReflectionTestUtils.setField(endpoint, "id", 7L);
        endpoint.setTenantId("tenant-a");
        endpoint.setName("receiver");
        endpoint.setUrl("http://localhost/webhook");
        endpoint.setEncryptedSecret("v1:encrypted");
        endpoint.setEventTypes("*");
        return endpoint;
    }
}

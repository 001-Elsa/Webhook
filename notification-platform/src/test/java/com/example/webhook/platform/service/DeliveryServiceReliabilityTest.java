package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.DeliveryStatus;
import com.example.webhook.platform.domain.DeliveryTask;
import com.example.webhook.platform.domain.EventRecord;
import com.example.webhook.platform.domain.EventStatus;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.queue.DeliveryQueue;
import com.example.webhook.platform.repo.DeliveryAttemptRepository;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeliveryServiceReliabilityTest {
    @Mock DeliveryTaskRepository deliveryRepository;
    @Mock DeliveryAttemptRepository attemptRepository;
    @Mock SignatureService signatureService;
    @Mock RateLimiter rateLimiter;
    @Mock DeliveryQueue deliveryQueue;
    @Mock TransactionTemplate transactionTemplate;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void compensationScannerRequeuesDueDatabaseTasks() {
        DeliveryTask first = task(11L);
        DeliveryTask second = task(12L);
        when(deliveryRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                any(Collection.class), any(Instant.class), any(Pageable.class))).thenReturn(List.of(first, second));

        service().recoverDueTasks();

        verify(deliveryQueue).enqueue(11L);
        verify(deliveryQueue).enqueue(12L);
    }

    @Test
    void manualRetryPublishesMessageOnlyAfterCommit() {
        DeliveryTask task = task(21L);
        task.setStatus(DeliveryStatus.DEAD);
        when(deliveryRepository.findById(21L)).thenReturn(Optional.of(task));
        when(deliveryRepository.save(task)).thenReturn(task);

        TransactionSynchronizationManager.initSynchronization();
        service().retryNow(21L);

        verify(deliveryQueue, never()).enqueue(any());
        TransactionSynchronizationManager.getSynchronizations().forEach(synchronization -> synchronization.afterCommit());
        verify(deliveryQueue).enqueue(21L);
    }

    @Test
    void failedDeliverySchedulesFiveSecondRetryBeforeMaxAttempts() throws Exception {
        HttpServer server = failingServer();
        try {
            DeliveryTask task = deliveryTaskWithEndpoint(31L, server, 0, 5);
            stubProcessingTransaction(task);
            when(signatureService.sign(any(), any(), any(), any())).thenReturn("signature");
            when(rateLimiter.tryAcquire(any(), anyInt())).thenReturn(true);

            DeliveryService.Outcome outcome = service().processDelivery(31L);

            assertThat(outcome).isEqualTo(DeliveryService.Outcome.RETRY);
            assertThat(task.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
            assertThat(task.getAttemptCount()).isEqualTo(1);
            assertThat(task.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(3));
            assertThat(task.getNextAttemptAt()).isBefore(Instant.now().plusSeconds(8));
            verify(attemptRepository).save(any());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failedDeliveryEntersDeadAfterMaxAttempts() throws Exception {
        HttpServer server = failingServer();
        try {
            DeliveryTask task = deliveryTaskWithEndpoint(41L, server, 1, 2);
            stubProcessingTransaction(task);
            when(signatureService.sign(any(), any(), any(), any())).thenReturn("signature");
            when(rateLimiter.tryAcquire(any(), anyInt())).thenReturn(true);

            DeliveryService.Outcome outcome = service().processDelivery(41L);

            assertThat(outcome).isEqualTo(DeliveryService.Outcome.DEAD);
            assertThat(task.getStatus()).isEqualTo(DeliveryStatus.DEAD);
            assertThat(task.getAttemptCount()).isEqualTo(2);
            verify(attemptRepository).save(any());
        } finally {
            server.stop(0);
        }
    }

    private DeliveryService service() {
        return new DeliveryService(deliveryRepository, attemptRepository, signatureService, rateLimiter, deliveryQueue,
                RestClient.builder(), new SimpleMeterRegistry(), transactionTemplate, 100);
    }

    private DeliveryTask task(Long id) {
        DeliveryTask task = new DeliveryTask();
        ReflectionTestUtils.setField(task, "id", id);
        task.setStatus(DeliveryStatus.PENDING);
        task.setNextAttemptAt(Instant.now().minusSeconds(1));
        return task;
    }

    private void stubProcessingTransaction(DeliveryTask task) {
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(deliveryRepository.findWithEventAndEndpointById(task.getId())).thenReturn(Optional.of(task));
        when(deliveryRepository.claimDueTask(eq(task.getId()), any(Collection.class), any(Instant.class), any(), any()))
                .thenReturn(1);
    }

    private DeliveryTask deliveryTaskWithEndpoint(Long id, HttpServer server, int attemptCount, int maxAttempts) {
        EventRecord event = new EventRecord();
        event.setTenantId("tenant-a");
        event.setAppId("app-a");
        event.setEventId("evt-" + id);
        event.setType("order.created");
        event.setPayload("{\"id\":" + id + "}");
        event.setStatus(EventStatus.DISPATCHING);

        WebhookEndpoint endpoint = new WebhookEndpoint();
        ReflectionTestUtils.setField(endpoint, "id", id);
        endpoint.setTenantId("tenant-a");
        endpoint.setName("receiver-" + id);
        endpoint.setUrl("http://localhost:" + server.getAddress().getPort() + "/webhook");
        endpoint.setSecret("secret");
        endpoint.setMaxAttempts(maxAttempts);
        endpoint.setRateLimitPerMinute(100);

        DeliveryTask task = task(id);
        task.setAttemptCount(attemptCount);
        task.setEvent(event);
        task.setEndpoint(endpoint);
        return task;
    }

    private HttpServer failingServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        return server;
    }
}

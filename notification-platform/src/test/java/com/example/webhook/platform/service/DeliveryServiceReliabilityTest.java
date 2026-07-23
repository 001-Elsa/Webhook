package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.DeliveryStatus;
import com.example.webhook.platform.domain.DeliveryTask;
import com.example.webhook.platform.domain.EventRecord;
import com.example.webhook.platform.domain.EventStatus;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.queue.DeliveryQueue;
import com.example.webhook.platform.repo.DeliveryAttemptRepository;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.EventRecordRepository;
import com.example.webhook.platform.security.WebhookSecretCipher;
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
    @Mock EventRecordRepository eventRepository;
    @Mock SignatureService signatureService;
    @Mock RateLimiter rateLimiter;
    @Mock DeliveryQueue deliveryQueue;
    @Mock OutboxService outboxService;
    @Mock WebhookSecretCipher secretCipher;
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
    void duplicateMessageForSucceededTaskIsSkippedWithoutCallingReceiver() {
        DeliveryTask task = task(13L);
        task.setStatus(DeliveryStatus.SUCCEEDED);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(deliveryRepository.findWithEventAndEndpointById(13L)).thenReturn(Optional.of(task));

        DeliveryService.Outcome outcome = service().processDelivery(13L);

        assertThat(outcome).isEqualTo(DeliveryService.Outcome.SKIPPED);
        verifyNoInteractions(signatureService);
    }

    @Test
    void manualRetryStoresOutboxMessageInSameTransaction() {
        DeliveryTask task = task(21L);
        task.setStatus(DeliveryStatus.DEAD);
        EventRecord event = new EventRecord();
        ReflectionTestUtils.setField(event, "id", 201L);
        event.setStatus(EventStatus.DEAD);
        task.setEvent(event);
        when(deliveryRepository.findByIdAndEventTenantId(21L, "tenant-a")).thenReturn(Optional.of(task));
        when(deliveryRepository.save(task)).thenReturn(task);

        TransactionSynchronizationManager.initSynchronization();
        service().retryNow(21L, "tenant-a");

        verify(outboxService).add(21L, com.example.webhook.platform.domain.OutboxMessageType.DELIVERY, 0);
    }

    @Test
    void failedDeliverySchedulesFiveSecondRetryBeforeMaxAttempts() throws Exception {
        HttpServer server = failingServer();
        try {
            DeliveryTask task = deliveryTaskWithEndpoint(31L, server, 0, 5);
            stubProcessingTransaction(task);
            when(signatureService.sign(any(), any(), any(), any())).thenReturn("signature");
            when(secretCipher.decrypt(any())).thenReturn("secret");
            when(rateLimiter.tryAcquire(any(), anyInt())).thenReturn(true);

            DeliveryService.Outcome outcome = service().processDelivery(31L);

            assertThat(outcome).isEqualTo(DeliveryService.Outcome.RETRY);
            assertThat(task.getStatus()).isEqualTo(DeliveryStatus.RETRYING);
            assertThat(task.getAttemptCount()).isEqualTo(1);
            assertThat(task.getLastStatusCode()).isEqualTo(500);
            assertThat(task.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(3));
            assertThat(task.getNextAttemptAt()).isBefore(Instant.now().plusSeconds(8));
            verify(attemptRepository).save(any());
            verify(outboxService).add(31L, com.example.webhook.platform.domain.OutboxMessageType.RETRY, 1);
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
            when(secretCipher.decrypt(any())).thenReturn("secret");
            when(rateLimiter.tryAcquire(any(), anyInt())).thenReturn(true);

            DeliveryService.Outcome outcome = service().processDelivery(41L);

            assertThat(outcome).isEqualTo(DeliveryService.Outcome.DEAD);
            assertThat(task.getStatus()).isEqualTo(DeliveryStatus.DEAD);
            assertThat(task.getAttemptCount()).isEqualTo(2);
            assertThat(task.getLastStatusCode()).isEqualTo(500);
            verify(attemptRepository).save(any());
            verify(outboxService).add(41L, com.example.webhook.platform.domain.OutboxMessageType.DEAD, 2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulDeliveryPreservesActualHttpStatusAndCompletesEvent() throws Exception {
        HttpServer server = successfulServer(202);
        try {
            DeliveryTask task = deliveryTaskWithEndpoint(51L, server, 0, 5);
            stubProcessingTransaction(task);
            when(signatureService.sign(any(), any(), any(), any())).thenReturn("signature");
            when(secretCipher.decrypt(any())).thenReturn("secret");
            when(rateLimiter.tryAcquire(any(), anyInt())).thenReturn(true);

            DeliveryService.Outcome outcome = service().processDelivery(51L);

            assertThat(outcome).isEqualTo(DeliveryService.Outcome.DONE);
            assertThat(task.getLastStatusCode()).isEqualTo(202);
            assertThat(task.getEvent().getStatus()).isEqualTo(EventStatus.COMPLETED);
        } finally {
            server.stop(0);
        }
    }

    private DeliveryService service() {
        return new DeliveryService(deliveryRepository, attemptRepository, eventRepository, signatureService, rateLimiter,
                deliveryQueue, outboxService, secretCipher, new DeliveryStateMachine(), new EventStateMachine(),
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
        endpoint.setEncryptedSecret("v1:encrypted");
        endpoint.setMaxAttempts(maxAttempts);
        endpoint.setRateLimitPerMinute(100);

        DeliveryTask task = task(id);
        task.setAttemptCount(attemptCount);
        task.setEvent(event);
        task.setEndpoint(endpoint);
        return task;
    }

    private HttpServer failingServer() throws Exception {
        return successfulServer(500);
    }

    private HttpServer successfulServer(int status) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/webhook", exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return server;
    }
}

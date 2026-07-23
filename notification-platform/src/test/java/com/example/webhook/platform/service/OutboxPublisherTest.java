package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.queue.DeliveryQueue;
import com.example.webhook.platform.repo.OutboxMessageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.time.Instant;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTest {
    @Test
    void confirmedPublishMarksOutboxMessagePublished() {
        OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
        DeliveryQueue queue = mock(DeliveryQueue.class);
        TransactionTemplate transactions = immediateTransactions();
        OutboxMessage message = message(1L, OutboxMessageType.DELIVERY);
        when(repository.claim(eq(1L), eq(OutboxStatus.PENDING), any(), any(), any())).thenReturn(1);
        when(repository.findById(1L)).thenReturn(Optional.of(message));
        OutboxPublisher publisher = new OutboxPublisher(repository, queue, transactions,
                new SimpleMeterRegistry(), 100);

        publisher.publishOne(1L);

        verify(queue).enqueue(99L);
        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    void brokerFailureKeepsMessagePendingForBackoffRetry() {
        OutboxMessageRepository repository = mock(OutboxMessageRepository.class);
        DeliveryQueue queue = mock(DeliveryQueue.class);
        TransactionTemplate transactions = immediateTransactions();
        OutboxMessage message = message(2L, OutboxMessageType.DEAD);
        when(repository.claim(eq(2L), eq(OutboxStatus.PENDING), any(), any(), any())).thenReturn(1);
        when(repository.findById(2L)).thenReturn(Optional.of(message));
        doThrow(new IllegalStateException("rabbit down")).when(queue).enqueueDead(99L);
        OutboxPublisher publisher = new OutboxPublisher(repository, queue, transactions,
                new SimpleMeterRegistry(), 100);

        publisher.publishOne(2L);

        assertThat(message.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(message.getPublishAttempts()).isEqualTo(1);
        assertThat(message.getNextAttemptAt()).isAfter(Instant.now());
    }

    private OutboxMessage message(Long id, OutboxMessageType type) {
        OutboxMessage message = new OutboxMessage();
        ReflectionTestUtils.setField(message, "id", id);
        message.setDeliveryId(99L);
        message.setMessageType(type);
        message.setAttemptNo(1);
        return message;
    }

    private TransactionTemplate immediateTransactions() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new TransactionTemplate(manager);
    }
}

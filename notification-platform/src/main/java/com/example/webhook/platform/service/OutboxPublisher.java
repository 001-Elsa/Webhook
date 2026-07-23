package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.OutboxMessage;
import com.example.webhook.platform.domain.OutboxStatus;
import com.example.webhook.platform.queue.DeliveryQueue;
import com.example.webhook.platform.repo.OutboxMessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final String workerId = "outbox-" + UUID.randomUUID();
    private final OutboxMessageRepository repository;
    private final DeliveryQueue queue;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final int batchSize;

    public OutboxPublisher(OutboxMessageRepository repository, DeliveryQueue queue, TransactionTemplate transactions,
                           MeterRegistry metrics, @Value("${webhook.outbox.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.queue = queue;
        this.transactions = transactions;
        this.metrics = metrics;
        this.batchSize = batchSize;
        metrics.gauge("webhook.outbox.pending", repository, value -> value.countByStatus(OutboxStatus.PENDING));
    }

    @Scheduled(fixedDelayString = "${webhook.outbox.fixed-delay-ms:250}")
    public void publishPending() {
        repository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                OutboxStatus.PENDING, Instant.now(), PageRequest.of(0, batchSize))
                .forEach(candidate -> publishOne(candidate.getId()));
    }

    public void publishOne(Long id) {
        OutboxMessage message = claim(id);
        if (message == null) return;
        try {
            switch (message.getMessageType()) {
                case DELIVERY -> queue.enqueue(message.getDeliveryId());
                case RETRY -> queue.enqueueRetry(message.getDeliveryId(), message.getAttemptNo());
                case DEAD -> queue.enqueueDead(message.getDeliveryId());
            }
            complete(id);
            metrics.counter("webhook.outbox.published", "type", message.getMessageType().name()).increment();
        } catch (RuntimeException ex) {
            fail(id, ex);
            log.warn("Outbox publish failed; message {} remains pending", id, ex);
            metrics.counter("webhook.outbox.failure", "type", message.getMessageType().name()).increment();
        }
    }

    private OutboxMessage claim(Long id) {
        return transactions.execute(status -> {
            Instant now = Instant.now();
            if (repository.claim(id, OutboxStatus.PENDING, now, workerId, now.plusSeconds(30)) != 1) return null;
            return repository.findById(id).orElse(null);
        });
    }

    private void complete(Long id) {
        transactions.executeWithoutResult(status -> repository.findById(id).ifPresent(message -> {
            message.setStatus(OutboxStatus.PUBLISHED);
            message.setLockedBy(null);
            message.setLockedUntil(null);
            message.setLastError(null);
            repository.save(message);
        }));
    }

    private void fail(Long id, RuntimeException failure) {
        transactions.executeWithoutResult(status -> repository.findById(id).ifPresent(message -> {
            int attempts = message.getPublishAttempts() + 1;
            message.setPublishAttempts(attempts);
            message.setNextAttemptAt(Instant.now().plusSeconds(Math.min(60, 1L << Math.min(attempts, 6))));
            message.setLastError(truncate(failure.getMessage(), 1000));
            message.setLockedBy(null);
            message.setLockedUntil(null);
            repository.save(message);
        }));
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}

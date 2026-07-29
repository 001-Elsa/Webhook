package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.OutboxMessage;
import com.example.webhook.platform.domain.OutboxStatus;
import com.example.webhook.platform.queue.DeliveryQueue;
import com.example.webhook.platform.repo.OutboxMessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Service
@ConditionalOnProperty(name = "eventrelay.roles.publisher", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private final String workerId = "outbox-" + UUID.randomUUID();
    private final OutboxMessageRepository repository;
    private final DeliveryQueue queue;
    private final TransactionTemplate transactions;
    private final MeterRegistry metrics;
    private final int batchSize;
    private final OutboxBatchStore batchStore;
    private final ThreadPoolExecutor publisherExecutor;
    private final int leaseSeconds;
    private final int maxPerTenant;

    /** Backwards-compatible constructor used by focused unit tests and embedded users. */
    public OutboxPublisher(OutboxMessageRepository repository, DeliveryQueue queue, TransactionTemplate transactions,
                           MeterRegistry metrics, int batchSize) {
        this(repository, queue, transactions, metrics, batchSize, null, 1, batchSize, 30, batchSize);
    }

    @Autowired
    public OutboxPublisher(OutboxMessageRepository repository, DeliveryQueue queue, TransactionTemplate transactions,
                           MeterRegistry metrics,
                           @Value("${webhook.outbox.batch-size:100}") int batchSize,
                           OutboxBatchStore batchStore,
                           @Value("${webhook.outbox.publisher-concurrency:8}") int concurrency,
                           @Value("${webhook.outbox.queue-capacity:200}") int queueCapacity,
                           @Value("${webhook.outbox.lease-seconds:30}") int leaseSeconds,
                           @Value("${webhook.outbox.max-per-tenant:100}") int maxPerTenant) {
        this.repository = repository;
        this.queue = queue;
        this.transactions = transactions;
        this.metrics = metrics;
        this.batchSize = batchSize;
        this.batchStore = batchStore;
        this.leaseSeconds = leaseSeconds;
        this.maxPerTenant = maxPerTenant;
        this.publisherExecutor = batchStore == null ? null : new ThreadPoolExecutor(
                concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "outbox-confirm");
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        metrics.gauge("webhook.outbox.pending", repository, value -> value.countByStatus(OutboxStatus.PENDING));
        if (publisherExecutor != null) {
            metrics.gauge("webhook.outbox.publish.queue.depth", publisherExecutor,
                    executor -> executor.getQueue().size());
            metrics.gauge("webhook.outbox.publish.active", publisherExecutor, ThreadPoolExecutor::getActiveCount);
        }
    }

    @Scheduled(fixedDelayString = "${webhook.outbox.fixed-delay-ms:250}")
    public void publishPending() {
        if (batchStore != null) {
            publishBatch();
            return;
        }
        repository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                        OutboxStatus.PENDING, Instant.now(), PageRequest.of(0, batchSize))
                .forEach(candidate -> publishOne(candidate.getId()));
    }

    private void publishBatch() {
        Instant now = Instant.now();
        List<OutboxMessage> claimed = batchStore.claimBatch(workerId, batchSize, maxPerTenant, now,
                now.plusSeconds(leaseSeconds));
        if (claimed.isEmpty()) return;
        metrics.summary("webhook.outbox.claim.batch.size").record(claimed.size());
        List<CompletableFuture<OutboxBatchStore.PublishResult>> futures = claimed.stream()
                .map(message -> CompletableFuture.supplyAsync(() -> publishClaimed(message), publisherExecutor))
                .toList();
        List<OutboxBatchStore.PublishResult> results = new ArrayList<>(futures.size());
        for (CompletableFuture<OutboxBatchStore.PublishResult> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException failure) {
                log.error("Unexpected outbox publish task failure; lease recovery will retry it", failure);
            }
        }
        if (!results.isEmpty()) batchStore.saveResults(results, Instant.now());
    }

    private OutboxBatchStore.PublishResult publishClaimed(OutboxMessage message) {
        try {
            send(message);
            Timer.builder("webhook.outbox.publish.latency")
                    .description("Time from outbox creation to confirmed RabbitMQ publish")
                    .register(metrics)
                    .record(Duration.between(message.getCreatedAt(), Instant.now()));
            metrics.counter("webhook.outbox.published", "type", message.getMessageType().name()).increment();
            return new OutboxBatchStore.PublishResult(message.getId(), true, message.getPublishAttempts(),
                    Instant.now(), null);
        } catch (RuntimeException failure) {
            int attempts = message.getPublishAttempts() + 1;
            long delay = Math.min(300, 1L << Math.min(attempts, 8));
            metrics.counter("webhook.outbox.failure", "type", message.getMessageType().name()).increment();
            return new OutboxBatchStore.PublishResult(message.getId(), false, attempts,
                    Instant.now().plusSeconds(delay), truncate(failure.getMessage(), 1000));
        }
    }

    public void publishOne(Long id) {
        OutboxMessage message = claim(id);
        if (message == null) return;
        try {
            send(message);
            complete(id);
            metrics.counter("webhook.outbox.published", "type", message.getMessageType().name()).increment();
        } catch (RuntimeException ex) {
            fail(id, ex);
            log.warn("Outbox publish failed; message {} remains pending", id, ex);
            metrics.counter("webhook.outbox.failure", "type", message.getMessageType().name()).increment();
        }
    }

    private void send(OutboxMessage message) {
        switch (message.getMessageType()) {
            // RETRY is released by MySQL next_attempt_at and uses the normal queue.
            case DELIVERY, RECOVERY, RETRY -> {
                if (message.getTraceParent() == null) queue.enqueue(message.getDeliveryId());
                else queue.enqueue(message.getDeliveryId(), message.getTraceParent());
            }
            case DEAD -> queue.enqueueDead(message.getDeliveryId());
        }
    }

    private OutboxMessage claim(Long id) {
        return transactions.execute(status -> {
            Instant now = Instant.now();
            if (repository.claim(id, OutboxStatus.PENDING, now, workerId, now.plusSeconds(leaseSeconds)) != 1) {
                return null;
            }
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
            message.setNextAttemptAt(Instant.now().plusSeconds(Math.min(300, 1L << Math.min(attempts, 8))));
            message.setLockedBy(null);
            message.setLockedUntil(null);
            message.setLastError(truncate(failure.getMessage(), 1000));
            message.setStatus(OutboxStatus.PENDING);
            repository.save(message);
        }));
    }

    @PreDestroy
    void shutdown() {
        if (publisherExecutor == null) return;
        publisherExecutor.shutdown();
        try {
            if (!publisherExecutor.awaitTermination(25, TimeUnit.SECONDS)) publisherExecutor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            publisherExecutor.shutdownNow();
        }
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}

package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.queue.DeliveryQueue;
import com.example.webhook.platform.repo.DeliveryAttemptRepository;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DeliveryService {
    public enum Outcome { DONE, RETRY, DEAD, SKIPPED }
    private final String workerId = "worker-" + UUID.randomUUID();
    private final DeliveryTaskRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final SignatureService signatureService;
    private final RateLimiter rateLimiter;
    private final DeliveryQueue deliveryQueue;
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final int recoveryBatchSize;

    public DeliveryService(DeliveryTaskRepository deliveryRepository, DeliveryAttemptRepository attemptRepository,
                           SignatureService signatureService, RateLimiter rateLimiter, DeliveryQueue deliveryQueue,
                           RestClient.Builder restClientBuilder, MeterRegistry meterRegistry,
                           @Value("${webhook.queue.recovery-batch-size:100}") int recoveryBatchSize) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.signatureService = signatureService;
        this.rateLimiter = rateLimiter;
        this.deliveryQueue = deliveryQueue;
        this.restClient = restClientBuilder.build();
        this.meterRegistry = meterRegistry;
        this.recoveryBatchSize = recoveryBatchSize;
    }

    /** Compensation scanner: the database is the source of truth when publish confirms fail or MQ restarts. */
    @Scheduled(fixedDelayString = "${webhook.dispatcher.fixed-delay-ms:5000}")
    public void recoverDueTasks() {
        deliveryRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), Instant.now(),
                PageRequest.of(0, recoveryBatchSize)).forEach(task -> deliveryQueue.enqueue(task.getId()));
    }

    @Transactional
    public Outcome processDelivery(Long deliveryId) {
        DeliveryTask snapshot = deliveryRepository.findWithEventAndEndpointById(deliveryId).orElse(null);
        if (snapshot == null || (snapshot.getStatus() != DeliveryStatus.PENDING && snapshot.getStatus() != DeliveryStatus.RETRYING)) {
            return Outcome.SKIPPED;
        }
        Instant now = Instant.now();
        if (snapshot.getNextAttemptAt().isAfter(now)) return Outcome.RETRY;
        boolean claimed = deliveryRepository.claimDueTask(deliveryId,
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), now, workerId, now.plusSeconds(60)) == 1;
        if (!claimed) return Outcome.SKIPPED;
        DeliveryTask task = deliveryRepository.findWithEventAndEndpointById(deliveryId)
                .orElseThrow(() -> new IllegalStateException("Claimed task disappeared: " + deliveryId));
        if (!rateLimiter.tryAcquire(task.getEndpoint().getId(), task.getEndpoint().getRateLimitPerMinute())) {
            task.setNextAttemptAt(now.plusSeconds(5));
            unlock(task);
            return Outcome.RETRY;
        }
        return deliver(task);
    }

    public int attemptCount(Long deliveryId) {
        return deliveryRepository.findById(deliveryId).map(DeliveryTask::getAttemptCount).orElse(1);
    }

    @Transactional
    public DeliveryTask retryNow(Long deliveryId) {
        DeliveryTask task = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery task not found: " + deliveryId));
        task.setStatus(DeliveryStatus.RETRYING);
        task.setNextAttemptAt(Instant.now());
        task.setLastError("Manual retry requested");
        DeliveryTask saved = deliveryRepository.save(task);
        deliveryQueue.enqueue(deliveryId);
        return saved;
    }

    @Transactional
    public int retryDeadTasks() {
        List<DeliveryTask> tasks = deliveryRepository.findTop100ByStatusOrderByUpdatedAtDesc(DeliveryStatus.DEAD);
        tasks.forEach(task -> {
            task.setStatus(DeliveryStatus.RETRYING);
            task.setNextAttemptAt(Instant.now());
            task.setLastError("Batch dead-letter replay requested");
            deliveryQueue.enqueue(task.getId());
        });
        return tasks.size();
    }

    private Outcome deliver(DeliveryTask task) {
        Timer.Sample sample = Timer.start(meterRegistry);
        EventRecord event = task.getEvent();
        WebhookEndpoint endpoint = task.getEndpoint();
        int attemptNo = task.getAttemptCount() + 1;
        Instant timestamp = Instant.now();
        String signature = signatureService.sign(endpoint.getSecret(), timestamp, event.getEventId(), event.getPayload());
        long started = System.nanoTime();
        DeliveryAttempt attempt = new DeliveryAttempt();
        attempt.setDelivery(task);
        attempt.setAttemptNo(attemptNo);
        Outcome outcome;
        try {
            String response = restClient.post().uri(endpoint.getUrl()).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Webhook-Event-Id", event.getEventId())
                    .header("X-Webhook-Event-Type", event.getType())
                    .header("X-Webhook-Delivery-Id", String.valueOf(task.getId()))
                    .header("X-Webhook-Timestamp", String.valueOf(timestamp.toEpochMilli()))
                    .header("X-Webhook-Signature", signature)
                    .header("X-Trace-Id", event.getTraceId() == null ? event.getEventId() : event.getTraceId())
                    .body(event.getPayload()).retrieve().body(String.class);
            attempt.setSuccess(true);
            attempt.setStatusCode(200);
            attempt.setResponseBody(truncate(response, 2000));
            task.setStatus(DeliveryStatus.SUCCEEDED);
            task.setLastError(null);
            task.setLastStatusCode(200);
            meterRegistry.counter("webhook.delivery.success", "endpoint", endpoint.getName()).increment();
            outcome = Outcome.DONE;
        } catch (RestClientException ex) {
            attempt.setSuccess(false);
            attempt.setErrorMessage(truncate(ex.getMessage(), 1000));
            task.setLastError(truncate(ex.getMessage(), 1000));
            task.setLastStatusCode(null);
            if (attemptNo >= endpoint.getMaxAttempts()) {
                task.setStatus(DeliveryStatus.DEAD);
                outcome = Outcome.DEAD;
            } else {
                task.setStatus(DeliveryStatus.RETRYING);
                task.setNextAttemptAt(Instant.now().plusSeconds(attemptNo <= 1 ? 5 : attemptNo <= 3 ? 30 : 120));
                outcome = Outcome.RETRY;
            }
            meterRegistry.counter("webhook.delivery.failure", "endpoint", endpoint.getName()).increment();
        } finally {
            task.setAttemptCount(attemptNo);
            unlock(task);
            attempt.setDurationMs(Duration.ofNanos(System.nanoTime() - started).toMillis());
            attemptRepository.save(attempt);
            deliveryRepository.save(task);
            sample.stop(Timer.builder("webhook.delivery.duration").tag("endpoint", endpoint.getName()).register(meterRegistry));
        }
        return outcome;
    }

    private void unlock(DeliveryTask task) { task.setLockedBy(null); task.setLockedUntil(null); }
    private String truncate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }
}

package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.queue.DeliveryQueue;
import com.example.webhook.platform.repo.DeliveryAttemptRepository;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.EventRecordRepository;
import com.example.webhook.platform.security.WebhookSecretCipher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
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
    private final EventRecordRepository eventRepository;
    private final SignatureService signatureService;
    private final RateLimiter rateLimiter;
    private final DeliveryQueue deliveryQueue;
    private final OutboxService outboxService;
    private final WebhookSecretCipher secretCipher;
    private final DeliveryStateMachine deliveryStateMachine;
    private final EventStateMachine eventStateMachine;
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;
    private final int recoveryBatchSize;

    public DeliveryService(DeliveryTaskRepository deliveryRepository, DeliveryAttemptRepository attemptRepository,
                           EventRecordRepository eventRepository,
                           SignatureService signatureService, RateLimiter rateLimiter, DeliveryQueue deliveryQueue,
                           OutboxService outboxService,
                           WebhookSecretCipher secretCipher,
                           DeliveryStateMachine deliveryStateMachine, EventStateMachine eventStateMachine,
                           RestClient.Builder restClientBuilder, MeterRegistry meterRegistry,
                           TransactionTemplate transactionTemplate,
                           @Value("${webhook.queue.recovery-batch-size:100}") int recoveryBatchSize) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.signatureService = signatureService;
        this.rateLimiter = rateLimiter;
        this.deliveryQueue = deliveryQueue;
        this.outboxService = outboxService;
        this.secretCipher = secretCipher;
        this.deliveryStateMachine = deliveryStateMachine;
        this.eventStateMachine = eventStateMachine;
        this.restClient = restClientBuilder.build();
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = transactionTemplate;
        this.recoveryBatchSize = recoveryBatchSize;
    }

    /** Compensation scanner: the database is the source of truth when publish confirms fail or MQ restarts. */
    @Scheduled(fixedDelayString = "${webhook.dispatcher.fixed-delay-ms:5000}")
    public void recoverDueTasks() {
        deliveryRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), Instant.now(),
                PageRequest.of(0, recoveryBatchSize)).forEach(task -> {
                    deliveryQueue.enqueue(task.getId());
                    meterRegistry.counter("webhook.delivery.recovered").increment();
                });
    }

    public Outcome processDelivery(Long deliveryId) {
        ClaimedDelivery claimed = claimDelivery(deliveryId);
        if (claimed.outcome() != null) return claimed.outcome();
        DeliveryResult result = deliver(claimed.task());
        return saveResult(deliveryId, result);
    }

    @Transactional
    public DeliveryTask retryNow(Long deliveryId, String tenantId) {
        DeliveryTask task = deliveryRepository.findByIdAndEventTenantId(deliveryId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery task not found: " + deliveryId));
        if (task.getStatus() != DeliveryStatus.DEAD) {
            throw new IllegalArgumentException("Only DEAD delivery tasks can be manually retried");
        }
        deliveryStateMachine.transition(task, DeliveryStatus.RETRYING);
        task.setNextAttemptAt(Instant.now());
        task.setLastError("Manual retry requested");
        DeliveryTask saved = deliveryRepository.save(task);
        outboxService.add(deliveryId, OutboxMessageType.DELIVERY, task.getAttemptCount());
        refreshEventStatus(task.getEvent());
        return saved;
    }

    @Transactional
    public int retryDeadTasks(String tenantId) {
        List<DeliveryTask> tasks = deliveryRepository.findTop100ByEventTenantIdAndStatusOrderByUpdatedAtDesc(
                tenantId, DeliveryStatus.DEAD);
        tasks.forEach(task -> {
            deliveryStateMachine.transition(task, DeliveryStatus.RETRYING);
            task.setNextAttemptAt(Instant.now());
            task.setLastError("Batch dead-letter replay requested");
            outboxService.add(task.getId(), OutboxMessageType.DELIVERY, task.getAttemptCount());
            refreshEventStatus(task.getEvent());
        });
        return tasks.size();
    }

    @Transactional
    public void markUnexpectedFailureDead(Long deliveryId, String error) {
        DeliveryTask task = deliveryRepository.findWithEventAndEndpointById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery task not found: " + deliveryId));
        if (task.getStatus() == DeliveryStatus.SUCCEEDED || task.getStatus() == DeliveryStatus.DEAD) return;
        deliveryStateMachine.transition(task, DeliveryStatus.DEAD);
        task.setLastError(truncate(error, 1000));
        unlock(task);
        deliveryRepository.save(task);
        outboxService.add(task.getId(), OutboxMessageType.DEAD, task.getAttemptCount());
        refreshEventStatus(task.getEvent());
    }

    private ClaimedDelivery claimDelivery(Long deliveryId) {
        return transactionTemplate.execute(status -> {
            DeliveryTask snapshot = deliveryRepository.findWithEventAndEndpointById(deliveryId).orElse(null);
            if (snapshot != null && snapshot.getStatus() == DeliveryStatus.DEAD) {
                return new ClaimedDelivery(null, Outcome.DEAD);
            }
            if (snapshot == null || (snapshot.getStatus() != DeliveryStatus.PENDING && snapshot.getStatus() != DeliveryStatus.RETRYING)) {
                return new ClaimedDelivery(null, Outcome.SKIPPED);
            }
            Instant now = Instant.now();
            if (snapshot.getNextAttemptAt().isAfter(now)) return new ClaimedDelivery(null, Outcome.RETRY);
            boolean claimed = deliveryRepository.claimDueTask(deliveryId,
                    List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), now, workerId, now.plusSeconds(60)) == 1;
            if (!claimed) return new ClaimedDelivery(null, Outcome.SKIPPED);
            DeliveryTask task = deliveryRepository.findWithEventAndEndpointById(deliveryId)
                    .orElseThrow(() -> new IllegalStateException("Claimed task disappeared: " + deliveryId));
            if (!rateLimiter.tryAcquire(task.getEndpoint().getId(), task.getEndpoint().getRateLimitPerMinute())) {
                task.setNextAttemptAt(now.plusSeconds(5));
                unlock(task);
                deliveryRepository.save(task);
                return new ClaimedDelivery(null, Outcome.RETRY);
            }
            return new ClaimedDelivery(task, null);
        });
    }

    private DeliveryResult deliver(DeliveryTask task) {
        Timer.Sample sample = Timer.start(meterRegistry);
        EventRecord event = task.getEvent();
        WebhookEndpoint endpoint = task.getEndpoint();
        int attemptNo = task.getAttemptCount() + 1;
        Instant timestamp = Instant.now();
        String signature = signatureService.sign(secretCipher.decrypt(endpoint.getEncryptedSecret()), timestamp,
                event.getEventId(), event.getPayload());
        long started = System.nanoTime();
        Outcome outcome = Outcome.RETRY;
        DeliveryResult result = new DeliveryResult(attemptNo);
        try {
            var response = restClient.post().uri(endpoint.getUrl()).contentType(MediaType.APPLICATION_JSON)
                    .header("X-Webhook-Event-Id", event.getEventId())
                    .header("X-Webhook-Event-Type", event.getType())
                    .header("X-Webhook-Delivery-Id", String.valueOf(task.getId()))
                    .header("X-Webhook-Timestamp", String.valueOf(timestamp.toEpochMilli()))
                    .header("X-Webhook-Signature", signature)
                    .header("X-Trace-Id", event.getTraceId() == null ? event.getEventId() : event.getTraceId())
                    .body(event.getPayload()).retrieve().toEntity(String.class);
            result.success = true;
            result.statusCode = response.getStatusCode().value();
            result.responseBody = truncate(response.getBody(), 2000);
            meterRegistry.counter("webhook.delivery.success").increment();
            outcome = Outcome.DONE;
        } catch (RestClientResponseException ex) {
            result.success = false;
            result.statusCode = ex.getStatusCode().value();
            result.responseBody = truncate(ex.getResponseBodyAsString(), 2000);
            result.errorMessage = truncate(ex.getMessage(), 1000);
            if (attemptNo >= endpoint.getMaxAttempts()) outcome = Outcome.DEAD;
            else result.nextAttemptAt = Instant.now().plusSeconds(retryDelaySeconds(attemptNo));
            meterRegistry.counter("webhook.delivery.failure").increment();
        } catch (RestClientException ex) {
            result.success = false;
            result.errorMessage = truncate(ex.getMessage(), 1000);
            if (attemptNo >= endpoint.getMaxAttempts()) {
                outcome = Outcome.DEAD;
            } else {
                result.nextAttemptAt = Instant.now().plusSeconds(retryDelaySeconds(attemptNo));
                outcome = Outcome.RETRY;
            }
            meterRegistry.counter("webhook.delivery.failure").increment();
        } finally {
            result.outcome = outcome;
            result.durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            sample.stop(Timer.builder("webhook.delivery.duration").register(meterRegistry));
        }
        return result;
    }

    private Outcome saveResult(Long deliveryId, DeliveryResult result) {
        return transactionTemplate.execute(status -> {
            DeliveryTask task = deliveryRepository.findWithEventAndEndpointById(deliveryId)
                    .orElseThrow(() -> new IllegalStateException("Delivery task disappeared: " + deliveryId));
            DeliveryAttempt attempt = new DeliveryAttempt();
            attempt.setDelivery(task);
            attempt.setAttemptNo(result.attemptNo);
            attempt.setSuccess(result.success);
            attempt.setStatusCode(result.statusCode);
            attempt.setResponseBody(result.responseBody);
            attempt.setErrorMessage(result.errorMessage);
            attempt.setDurationMs(result.durationMs);
            task.setAttemptCount(result.attemptNo);
            if (result.outcome == Outcome.DONE) {
                deliveryStateMachine.transition(task, DeliveryStatus.SUCCEEDED);
                task.setLastError(null);
                task.setLastStatusCode(result.statusCode);
            } else if (result.outcome == Outcome.DEAD) {
                deliveryStateMachine.transition(task, DeliveryStatus.DEAD);
                task.setLastError(result.errorMessage);
                task.setLastStatusCode(result.statusCode);
            } else {
                deliveryStateMachine.transition(task, DeliveryStatus.RETRYING);
                task.setNextAttemptAt(result.nextAttemptAt);
                task.setLastError(result.errorMessage);
                task.setLastStatusCode(result.statusCode);
            }
            unlock(task);
            attemptRepository.save(attempt);
            deliveryRepository.save(task);
            if (result.outcome == Outcome.RETRY) {
                outboxService.add(task.getId(), OutboxMessageType.RETRY, result.attemptNo);
            } else if (result.outcome == Outcome.DEAD) {
                outboxService.add(task.getId(), OutboxMessageType.DEAD, result.attemptNo);
            }
            meterRegistry.counter("webhook.delivery.outcome", "outcome", result.outcome.name()).increment();
            refreshEventStatus(task.getEvent());
            return result.outcome;
        });
    }

    private void refreshEventStatus(EventRecord event) {
        long pending = deliveryRepository.countByEventIdAndStatus(event.getId(), DeliveryStatus.PENDING)
                + deliveryRepository.countByEventIdAndStatus(event.getId(), DeliveryStatus.RETRYING);
        long succeeded = deliveryRepository.countByEventIdAndStatus(event.getId(), DeliveryStatus.SUCCEEDED);
        long dead = deliveryRepository.countByEventIdAndStatus(event.getId(), DeliveryStatus.DEAD);
        if (pending > 0) eventStateMachine.transition(event, EventStatus.DISPATCHING);
        else if (dead == 0) eventStateMachine.transition(event, EventStatus.COMPLETED);
        else if (succeeded == 0) eventStateMachine.transition(event, EventStatus.DEAD);
        else eventStateMachine.transition(event, EventStatus.PARTIALLY_FAILED);
        eventRepository.save(event);
    }

    private long retryDelaySeconds(int attemptNo) { return attemptNo <= 1 ? 5 : attemptNo <= 3 ? 30 : 120; }
    private void unlock(DeliveryTask task) { task.setLockedBy(null); task.setLockedUntil(null); }
    private String truncate(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }

    private record ClaimedDelivery(DeliveryTask task, Outcome outcome) { }

    private static final class DeliveryResult {
        private final int attemptNo;
        private boolean success;
        private Integer statusCode;
        private String responseBody;
        private String errorMessage;
        private long durationMs;
        private Instant nextAttemptAt;
        private Outcome outcome;

        private DeliveryResult(int attemptNo) {
            this.attemptNo = attemptNo;
        }
    }
}

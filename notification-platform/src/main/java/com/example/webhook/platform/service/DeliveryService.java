package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.repo.DeliveryAttemptRepository;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.EventRecordRepository;
import com.example.webhook.platform.security.WebhookSecretCipher;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class DeliveryService {
    public enum Outcome { DONE, RETRY, DEAD, SKIPPED }
    private final String workerId = "worker-" + UUID.randomUUID();
    private final DeliveryTaskRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final EventRecordRepository eventRepository;
    private final SignatureService signatureService;
    private final RateLimiter rateLimiter;
    private final OutboxService outboxService;
    private final WebhookSecretCipher secretCipher;
    private final DeliveryStateMachine deliveryStateMachine;
    private final EventStateMachine eventStateMachine;
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate transactionTemplate;
    private final int recoveryBatchSize;
    private final long retryBaseDelaySeconds;
    private final long retryMaxDelaySeconds;
    private final long retryJitterSeconds;
    private final EndpointGuard endpointGuard;
    private final TenantQuotaService tenantQuotas;

    public DeliveryService(DeliveryTaskRepository deliveryRepository, DeliveryAttemptRepository attemptRepository,
                           EventRecordRepository eventRepository,
                           SignatureService signatureService, RateLimiter rateLimiter,
                           OutboxService outboxService,
                           WebhookSecretCipher secretCipher,
                           DeliveryStateMachine deliveryStateMachine, EventStateMachine eventStateMachine,
                           RestClient.Builder restClientBuilder, MeterRegistry meterRegistry,
                           TransactionTemplate transactionTemplate,
                           @Value("${webhook.queue.recovery-batch-size:100}") int recoveryBatchSize,
                           @Value("${webhook.retry.base-delay-seconds:5}") long retryBaseDelaySeconds,
                           @Value("${webhook.retry.max-delay-seconds:300}") long retryMaxDelaySeconds,
                           @Value("${webhook.retry.jitter-seconds:5}") long retryJitterSeconds) {
        this(deliveryRepository, attemptRepository, eventRepository, signatureService, rateLimiter, outboxService,
                secretCipher, deliveryStateMachine, eventStateMachine, restClientBuilder, meterRegistry,
                transactionTemplate, recoveryBatchSize, retryBaseDelaySeconds, retryMaxDelaySeconds,
                retryJitterSeconds, null, null);
    }

    @Autowired
    public DeliveryService(DeliveryTaskRepository deliveryRepository, DeliveryAttemptRepository attemptRepository,
                           EventRecordRepository eventRepository,
                           SignatureService signatureService, RateLimiter rateLimiter,
                           OutboxService outboxService,
                           WebhookSecretCipher secretCipher,
                           DeliveryStateMachine deliveryStateMachine, EventStateMachine eventStateMachine,
                           RestClient.Builder restClientBuilder, MeterRegistry meterRegistry,
                           TransactionTemplate transactionTemplate,
                           @Value("${webhook.queue.recovery-batch-size:100}") int recoveryBatchSize,
                           @Value("${webhook.retry.base-delay-seconds:5}") long retryBaseDelaySeconds,
                           @Value("${webhook.retry.max-delay-seconds:300}") long retryMaxDelaySeconds,
                           @Value("${webhook.retry.jitter-seconds:5}") long retryJitterSeconds,
                           EndpointGuard endpointGuard, TenantQuotaService tenantQuotas) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.eventRepository = eventRepository;
        this.signatureService = signatureService;
        this.rateLimiter = rateLimiter;
        this.outboxService = outboxService;
        this.secretCipher = secretCipher;
        this.deliveryStateMachine = deliveryStateMachine;
        this.eventStateMachine = eventStateMachine;
        this.restClient = restClientBuilder.build();
        this.meterRegistry = meterRegistry;
        this.transactionTemplate = transactionTemplate;
        this.recoveryBatchSize = recoveryBatchSize;
        this.retryBaseDelaySeconds = retryBaseDelaySeconds;
        this.retryMaxDelaySeconds = retryMaxDelaySeconds;
        this.retryJitterSeconds = retryJitterSeconds;
        this.endpointGuard = endpointGuard;
        this.tenantQuotas = tenantQuotas;
    }

    /** Compensation scanner only creates idempotent outbox records; it never writes directly to RabbitMQ. */
    public void recoverDueTasks() {
        if (tenantQuotas != null) {
            recoverFairly();
            return;
        }
        deliveryRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), Instant.now(),
                PageRequest.of(0, recoveryBatchSize)).forEach(task -> {
                    if (outboxService.addRecoveryIfAbsent(task.getId(), task.getAttemptCount())) {
                        meterRegistry.counter("webhook.delivery.recovered").increment();
                    }
                });
    }

    private void recoverFairly() {
        Instant now = Instant.now();
        List<String> tenants = deliveryRepository.findTenantsWithDueTasks(now, Math.min(recoveryBatchSize, 100));
        if (tenants.isEmpty()) return;
        int baseShare = Math.max(1, recoveryBatchSize / tenants.size());
        int scheduled = 0;
        for (String tenant : tenants) {
            int share = Math.min(recoveryBatchSize - scheduled,
                    baseShare * Math.max(1, tenantQuotas.schedulingWeight(tenant)));
            if (share <= 0) break;
            List<DeliveryTask> tasks =
                    deliveryRepository.findByEventTenantIdAndStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                            tenant, List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), now,
                            PageRequest.of(0, share));
            for (DeliveryTask task : tasks) {
                if (outboxService.addRecoveryIfAbsent(task.getId(), task.getAttemptCount())) {
                    meterRegistry.counter("webhook.delivery.recovered", "tenant", tenant).increment();
                }
                scheduled++;
            }
        }
    }

    public Outcome processDelivery(Long deliveryId) {
        ClaimedDelivery claimed = claimDelivery(deliveryId);
        if (claimed.outcome() != null) return claimed.outcome();
        Timer.builder("webhook.delivery.claim.wait")
                .register(meterRegistry)
                .record(Duration.between(claimed.task().getCreatedAt(), Instant.now()));
        EndpointGuard.Permit permit = endpointGuard == null
                ? EndpointGuard.Permit.acquiredNoop()
                : endpointGuard.tryAcquire(claimed.task().getEvent().getTenantId(), claimed.task().getEndpoint());
        if (!permit.acquired()) {
            deferDelivery(deliveryId, permit.rejectionReason());
            meterRegistry.counter("webhook.delivery.deferred", "reason", permit.rejectionReason()).increment();
            return Outcome.RETRY;
        }
        try (permit) {
            DeliveryResult result = deliver(claimed.task());
            return saveResult(deliveryId, result);
        }
    }

    private void deferDelivery(Long deliveryId, String reason) {
        transactionTemplate.executeWithoutResult(status ->
                deliveryRepository.findWithEventAndEndpointById(deliveryId).ifPresent(task -> {
                    task.setNextAttemptAt(Instant.now().plusSeconds(5));
                    task.setLastError("Deferred by " + reason);
                    unlock(task);
                    deliveryRepository.save(task);
                }));
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
                    .header("X-Webhook-Schema-Version", event.getSchemaVersion())
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
            if (isRetryable(ex.getStatusCode().value()) && attemptNo < endpoint.getMaxAttempts()) {
                result.nextAttemptAt = nextRetryAt(attemptNo, ex.getResponseHeaders());
                outcome = Outcome.RETRY;
            } else {
                outcome = Outcome.DEAD;
            }
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
                closeCircuit(task.getEndpoint());
            } else if (result.outcome == Outcome.DEAD) {
                deliveryStateMachine.transition(task, DeliveryStatus.DEAD);
                task.setLastError(result.errorMessage);
                task.setLastStatusCode(result.statusCode);
                recordEndpointFailure(task.getEndpoint());
            } else {
                deliveryStateMachine.transition(task, DeliveryStatus.RETRYING);
                task.setNextAttemptAt(result.nextAttemptAt);
                task.setLastError(result.errorMessage);
                task.setLastStatusCode(result.statusCode);
                recordEndpointFailure(task.getEndpoint());
            }
            unlock(task);
            attemptRepository.save(attempt);
            deliveryRepository.save(task);
            if (result.outcome == Outcome.DEAD) {
                outboxService.add(task.getId(), OutboxMessageType.DEAD, result.attemptNo);
            }
            meterRegistry.counter("webhook.delivery.outcome", "outcome", result.outcome.name()).increment();
            if (result.outcome == Outcome.DONE || result.outcome == Outcome.DEAD) {
                Timer.builder("webhook.delivery.end.to.end")
                        .description("Event accepted to terminal delivery state")
                        .register(meterRegistry)
                        .record(Duration.between(task.getEvent().getCreatedAt(), Instant.now()));
            }
            refreshEventStatus(task.getEvent());
            return result.outcome;
        });
    }

    private void recordEndpointFailure(WebhookEndpoint endpoint) {
        if (endpoint.getCircuitState() == CircuitState.HALF_OPEN) {
            openCircuit(endpoint);
            return;
        }
        int failures = endpoint.getConsecutiveFailures() + 1;
        endpoint.setConsecutiveFailures(failures);
        if (failures >= endpoint.getFailureThreshold()) {
            openCircuit(endpoint);
        }
    }

    private void openCircuit(WebhookEndpoint endpoint) {
        endpoint.setCircuitState(CircuitState.OPEN);
        endpoint.setCircuitOpenUntil(Instant.now().plusSeconds(endpoint.getCircuitCooldownSeconds()));
        endpoint.setHalfOpenProbes(0);
        meterRegistry.counter("webhook.endpoint.circuit.opened").increment();
        meterRegistry.counter("webhook.endpoint.circuit.state", "state", "OPEN").increment();
    }

    private void closeCircuit(WebhookEndpoint endpoint) {
        CircuitState previous = endpoint.getCircuitState();
        endpoint.setConsecutiveFailures(0);
        endpoint.setCircuitOpenUntil(null);
        endpoint.setCircuitState(CircuitState.CLOSED);
        endpoint.setHalfOpenProbes(0);
        if (previous != null && previous != CircuitState.CLOSED) {
            meterRegistry.counter("webhook.endpoint.circuit.state", "state", "CLOSED").increment();
        }
    }

    private void refreshEventStatus(EventRecord event) {
        var counts = deliveryRepository.countStatusesByEventId(event.getId(),
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), DeliveryStatus.SUCCEEDED, DeliveryStatus.DEAD);
        long pending = counts.pending();
        long succeeded = counts.succeeded();
        long dead = counts.dead();
        if (pending > 0) eventStateMachine.transition(event, EventStatus.DISPATCHING);
        else if (dead == 0) eventStateMachine.transition(event, EventStatus.COMPLETED);
        else if (succeeded == 0) eventStateMachine.transition(event, EventStatus.DEAD);
        else eventStateMachine.transition(event, EventStatus.PARTIALLY_FAILED);
        eventRepository.save(event);
    }

    private boolean isRetryable(int statusCode) {
        return statusCode == 408 || statusCode == 425 || statusCode == 429
                || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private Instant nextRetryAt(int attemptNo, HttpHeaders headers) {
        Long retryAfter = retryAfterSeconds(headers);
        return Instant.now().plusSeconds(retryAfter == null ? retryDelaySeconds(attemptNo) : retryAfter);
    }

    private Long retryAfterSeconds(HttpHeaders headers) {
        if (headers == null) return null;
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) return null;
        try { return Math.min(retryMaxDelaySeconds, Math.max(0, Long.parseLong(value.trim()))); }
        catch (NumberFormatException ignored) {
            try {
                long seconds = Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toSeconds();
                return Math.min(retryMaxDelaySeconds, Math.max(0, seconds));
            } catch (RuntimeException invalidDate) { return null; }
        }
    }

    private long retryDelaySeconds(int attemptNo) {
        int exponent = Math.min(Math.max(attemptNo - 1, 0), 30);
        long exponential = retryBaseDelaySeconds > Long.MAX_VALUE >> exponent ? Long.MAX_VALUE
                : retryBaseDelaySeconds << exponent;
        long bounded = Math.min(retryMaxDelaySeconds, exponential);
        long jitter = retryJitterSeconds <= 0 ? 0 : ThreadLocalRandom.current().nextLong(retryJitterSeconds + 1);
        return Math.min(retryMaxDelaySeconds, bounded + jitter);
    }
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

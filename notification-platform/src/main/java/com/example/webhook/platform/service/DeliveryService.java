package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.repo.DeliveryAttemptRepository;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
    private final String workerId = "worker-" + UUID.randomUUID();
    private final DeliveryTaskRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final SignatureService signatureService;
    private final RateLimiter rateLimiter;
    private final RestClient restClient;
    private final MeterRegistry meterRegistry;

    public DeliveryService(DeliveryTaskRepository deliveryRepository, DeliveryAttemptRepository attemptRepository,
                           SignatureService signatureService, RateLimiter rateLimiter,
                           RestClient.Builder restClientBuilder, MeterRegistry meterRegistry) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.signatureService = signatureService;
        this.rateLimiter = rateLimiter;
        this.restClient = restClientBuilder.build();
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${webhook.dispatcher.fixed-delay-ms:2000}")
    @Transactional
    public void dispatchDueTasks() {
        List<DeliveryTask> tasks = deliveryRepository.findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), Instant.now(), PageRequest.of(0, 20));
        for (DeliveryTask task : tasks) {
            boolean claimed = deliveryRepository.claimDueTask(
                    task.getId(),
                    List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING),
                    Instant.now(),
                    workerId,
                    Instant.now().plusSeconds(30)) == 1;
            if (!claimed) {
                continue;
            }
            DeliveryTask claimedTask = deliveryRepository.findWithEventAndEndpointById(task.getId())
                    .orElseThrow(() -> new IllegalStateException("Claimed task disappeared: " + task.getId()));
            if (!rateLimiter.tryAcquire(claimedTask.getEndpoint().getId(), claimedTask.getEndpoint().getRateLimitPerMinute())) {
                claimedTask.setNextAttemptAt(Instant.now().plusSeconds(5));
                claimedTask.setLockedBy(null);
                claimedTask.setLockedUntil(null);
                continue;
            }
            deliver(claimedTask);
        }
    }

    @Transactional
    public DeliveryTask retryNow(Long deliveryId) {
        DeliveryTask task = deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new IllegalArgumentException("Delivery task not found: " + deliveryId));
        task.setStatus(DeliveryStatus.RETRYING);
        task.setNextAttemptAt(Instant.now());
        task.setLastError("Manual retry requested");
        return deliveryRepository.save(task);
    }

    @Transactional
    public int retryDeadTasks() {
        List<DeliveryTask> tasks = deliveryRepository.findTop100ByStatusOrderByUpdatedAtDesc(DeliveryStatus.DEAD);
        for (DeliveryTask task : tasks) {
            task.setStatus(DeliveryStatus.RETRYING);
            task.setNextAttemptAt(Instant.now());
            task.setLastError("Batch dead-letter replay requested");
        }
        return tasks.size();
    }

    public void deliver(DeliveryTask task) {
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
        try {
            String response = restClient.post()
                    .uri(endpoint.getUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Webhook-Event-Id", event.getEventId())
                    .header("X-Webhook-Event-Type", event.getType())
                    .header("X-Webhook-Delivery-Id", String.valueOf(task.getId()))
                    .header("X-Webhook-Timestamp", String.valueOf(timestamp.toEpochMilli()))
                    .header("X-Webhook-Signature", signature)
                    .header("X-Trace-Id", event.getTraceId() == null ? event.getEventId() : event.getTraceId())
                    .body(event.getPayload())
                    .retrieve()
                    .body(String.class);
            attempt.setSuccess(true);
            attempt.setStatusCode(200);
            attempt.setResponseBody(truncate(response, 2000));
            task.setStatus(DeliveryStatus.SUCCEEDED);
            task.setLastError(null);
            task.setLastStatusCode(200);
            meterRegistry.counter("webhook.delivery.success", "endpoint", endpoint.getName()).increment();
        } catch (RestClientException ex) {
            markFailure(task, attempt, attemptNo, ex);
            meterRegistry.counter("webhook.delivery.failure", "endpoint", endpoint.getName()).increment();
        } finally {
            task.setAttemptCount(attemptNo);
            task.setLockedBy(null);
            task.setLockedUntil(null);
            attempt.setDurationMs(Duration.ofNanos(System.nanoTime() - started).toMillis());
            attemptRepository.save(attempt);
            deliveryRepository.save(task);
            sample.stop(Timer.builder("webhook.delivery.duration").tag("endpoint", endpoint.getName()).register(meterRegistry));
        }
    }

    private void markFailure(DeliveryTask task, DeliveryAttempt attempt, int attemptNo, RestClientException ex) {
        attempt.setSuccess(false);
        attempt.setErrorMessage(truncate(ex.getMessage(), 1000));
        task.setLastError(truncate(ex.getMessage(), 1000));
        task.setLastStatusCode(null);
        if (attemptNo >= task.getEndpoint().getMaxAttempts()) {
            task.setStatus(DeliveryStatus.DEAD);
            task.setNextAttemptAt(Instant.now());
            return;
        }
        long delaySeconds = Math.min(300, (long) Math.pow(2, attemptNo));
        task.setStatus(DeliveryStatus.RETRYING);
        task.setNextAttemptAt(Instant.now().plusSeconds(delaySeconds));
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}

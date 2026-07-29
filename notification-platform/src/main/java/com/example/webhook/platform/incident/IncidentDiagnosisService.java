package com.example.webhook.platform.incident;

import com.example.webhook.platform.domain.CircuitState;
import com.example.webhook.platform.domain.IncidentDiagnosisRecord;
import com.example.webhook.platform.repo.*;
import com.example.webhook.platform.queue.RabbitTopology;
import com.example.webhook.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class IncidentDiagnosisService {
    private final DeliveryTaskRepository deliveries;
    private final DeliveryAttemptRepository attempts;
    private final OutboxMessageRepository outbox;
    private final IncidentDiagnosisRepository diagnoses;
    private final List<IncidentAdvisor> advisors;
    private final SensitiveDataRedactor redactor;
    private final ObjectMapper json;
    private final AmqpAdmin rabbit;
    private final MeterRegistry metrics;
    private final RunbookRetriever runbookRetriever;

    public IncidentDiagnosisService(DeliveryTaskRepository deliveries, DeliveryAttemptRepository attempts,
                                    OutboxMessageRepository outbox, IncidentDiagnosisRepository diagnoses,
                                    List<IncidentAdvisor> advisors, SensitiveDataRedactor redactor,
                                    ObjectMapper json, AmqpAdmin rabbit, MeterRegistry metrics,
                                    RunbookRetriever runbookRetriever) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.outbox = outbox;
        this.diagnoses = diagnoses;
        this.advisors = advisors;
        this.redactor = redactor;
        this.json = json;
        this.rabbit = rabbit;
        this.metrics = metrics;
        this.runbookRetriever = runbookRetriever;
    }

    public IncidentDiagnosis diagnose(Long deliveryId) {
        String tenant = RequestContext.principal().tenantId();
        var task = deliveries.findWithEventAndEndpointById(deliveryId)
                .filter(candidate -> tenant.equals(candidate.getEvent().getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Delivery not found: " + deliveryId));
        List<IncidentContext.Evidence> evidence = new ArrayList<>();
        evidence.add(new IncidentContext.Evidence("delivery:" + deliveryId,
                "DELIVERY_STATE", task.getStatus().name() + "; attempts=" + task.getAttemptCount()));
        if (task.getLastStatusCode() != null) {
            evidence.add(new IncidentContext.Evidence("delivery:" + deliveryId + ":status",
                    "HTTP_STATUS", String.valueOf(task.getLastStatusCode())));
        }
        if (task.getLastError() != null) {
            evidence.add(new IncidentContext.Evidence("delivery:" + deliveryId + ":error",
                    "SANITIZED_ERROR", redactor.redact(task.getLastError())));
        }
        attempts.findByDeliveryIdAndDeliveryEventTenantIdOrderByCreatedAtDesc(deliveryId, tenant).stream()
                .limit(5).forEach(attempt -> evidence.add(new IncidentContext.Evidence(
                        "attempt:" + attempt.getId(), "ATTEMPT",
                        "no=" + attempt.getAttemptNo() + "; status=" + attempt.getStatusCode()
                                + "; durationMs=" + attempt.getDurationMs()
                                + "; error=" + redactor.redact(attempt.getErrorMessage()))));

        Map<String, Number> signals = rabbitSignals();
        long backlog = outbox.countByStatus(com.example.webhook.platform.domain.OutboxStatus.PENDING);
        boolean paused = task.getEndpoint().getPausedAt() != null;
        CircuitState circuitState = task.getEndpoint().getCircuitState();
        boolean circuitOpen = circuitState == CircuitState.OPEN
                || circuitState == CircuitState.HALF_OPEN
                || (circuitState == null && task.getEndpoint().getCircuitOpenUntil() != null);
        String runbookExcerpt = runbookRetriever.retrieve(new RunbookRetriever.IncidentSignals(
                task.getLastStatusCode(),
                task.getStatus().name(),
                task.getLastError() == null ? null : redactor.redact(task.getLastError()),
                backlog,
                circuitOpen,
                paused));
        IncidentContext context = new IncidentContext(deliveryId, tenant, task.getStatus().name(),
                task.getLastStatusCode(), task.getAttemptCount(), paused, circuitOpen, backlog,
                List.copyOf(evidence), signals, runbookExcerpt);

        IncidentDiagnosis diagnosis = advisors.stream()
                .map(advisor -> advisor.diagnose(context))
                .flatMap(Optional::stream)
                .filter(candidate -> valid(candidate, evidence))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No incident advisor available"));
        persist(context, diagnosis);
        metrics.counter("webhook.incident.diagnosis", "analyzer", diagnosis.analyzer(),
                "category", diagnosis.category()).increment();
        return diagnosis;
    }

    private boolean valid(IncidentDiagnosis diagnosis, List<IncidentContext.Evidence> evidence) {
        if (diagnosis.category() == null || diagnosis.summary() == null
                || diagnosis.confidence() < 0 || diagnosis.confidence() > 1
                || diagnosis.summary().length() > 1000
                || diagnosis.recommendedActions() == null || diagnosis.recommendedActions().size() > 10) return false;
        Set<String> allowedEvidence = new HashSet<>(
                evidence.stream().map(IncidentContext.Evidence::id).toList());
        return diagnosis.evidenceIds() != null && allowedEvidence.containsAll(diagnosis.evidenceIds());
    }

    private void persist(IncidentContext context, IncidentDiagnosis diagnosis) {
        try {
            IncidentDiagnosisRecord record = new IncidentDiagnosisRecord();
            record.setTenantId(context.tenantId());
            record.setDeliveryId(context.deliveryId());
            record.setCategory(diagnosis.category());
            record.setConfidence(diagnosis.confidence());
            record.setSummary(diagnosis.summary());
            record.setEvidenceJson(json.writeValueAsString(diagnosis.evidenceIds()));
            record.setActionsJson(json.writeValueAsString(diagnosis.recommendedActions()));
            record.setReplayRecommended(diagnosis.replayRecommended());
            record.setAnalyzer(diagnosis.analyzer());
            record.setModel(diagnosis.model());
            record.setPromptVersion(diagnosis.promptVersion());
            diagnoses.save(record);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not audit incident diagnosis", failure);
        }
    }

    private Map<String, Number> rabbitSignals() {
        try {
            Properties properties = rabbit.getQueueProperties(RabbitTopology.DELIVERY_QUEUE);
            if (properties == null) return Map.of();
            return Map.of("rabbitReady", number(properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)),
                    "rabbitConsumers", number(properties.get(RabbitAdmin.QUEUE_CONSUMER_COUNT)));
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    private Number number(Object value) { return value instanceof Number number ? number : 0; }
}

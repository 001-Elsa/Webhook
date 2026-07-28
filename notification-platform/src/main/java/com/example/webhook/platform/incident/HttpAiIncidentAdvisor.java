package com.example.webhook.platform.incident;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.Optional;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "webhook.ai.enabled", havingValue = "true")
public class HttpAiIncidentAdvisor implements IncidentAdvisor {
    private final RestClient client;
    private final String model;
    private final String promptVersion;
    private final int maxTokens;
    private final MeterRegistry metrics;

    public HttpAiIncidentAdvisor(RestClient.Builder builder,
            @Value("${webhook.ai.endpoint}") String endpoint,
            @Value("${webhook.ai.api-key}") String apiKey,
            @Value("${webhook.ai.model}") String model,
            @Value("${webhook.ai.prompt-version:incident-v1}") String promptVersion,
            @Value("${webhook.ai.max-tokens:800}") int maxTokens,
            MeterRegistry metrics) {
        this.client = builder.baseUrl(endpoint).defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.model = model;
        this.promptVersion = promptVersion;
        this.maxTokens = maxTokens;
        this.metrics = metrics;
    }

    @Override
    public Optional<IncidentDiagnosis> diagnose(IncidentContext context) {
        try {
            IncidentDiagnosis response = client.post().uri("/")
                    .body(Map.of("model", model, "promptVersion", promptVersion,
                            "maxTokens", maxTokens, "context", context))
                    .retrieve().body(IncidentDiagnosis.class);
            if (response == null) return Optional.empty();
            return Optional.of(new IncidentDiagnosis(response.category(), response.confidence(), response.summary(),
                    response.evidenceIds(), response.recommendedActions(), response.replayRecommended(),
                    "ai-read-only", model, promptVersion));
        } catch (RuntimeException unavailable) {
            metrics.counter("webhook.ai.diagnosis.failure").increment();
            return Optional.empty();
        }
    }
}

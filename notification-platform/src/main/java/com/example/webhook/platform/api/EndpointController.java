package com.example.webhook.platform.api;

import com.example.webhook.platform.api.dto.CreateEndpointRequest;
import com.example.webhook.platform.api.dto.EndpointResponse;
import com.example.webhook.platform.api.dto.UpdateEndpointPolicyRequest;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import com.example.webhook.platform.security.RequestContext;
import com.example.webhook.platform.service.WebhookUrlValidator;
import com.example.webhook.platform.security.WebhookSecretCipher;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.time.Instant;
import com.example.webhook.platform.service.AuditService;
import com.example.webhook.platform.service.EndpointMatcher;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/endpoints")
public class EndpointController {
    private final WebhookEndpointRepository repository;
    private final WebhookUrlValidator urlValidator;
    private final WebhookSecretCipher secretCipher;
    private final AuditService audit;

    public EndpointController(WebhookEndpointRepository repository, WebhookUrlValidator urlValidator,
                              WebhookSecretCipher secretCipher) {
        this(repository, urlValidator, secretCipher, null);
    }

    @Autowired
    public EndpointController(WebhookEndpointRepository repository, WebhookUrlValidator urlValidator,
                              WebhookSecretCipher secretCipher, AuditService audit) {
        this.repository = repository;
        this.urlValidator = urlValidator;
        this.secretCipher = secretCipher;
        this.audit = audit;
    }

    @GetMapping
    public List<EndpointResponse> list() {
        return repository.findByTenantId(RequestContext.principal().tenantId()).stream().map(EndpointResponse::from).toList();
    }

    @PostMapping
    public EndpointResponse create(@Valid @RequestBody CreateEndpointRequest request) {
        urlValidator.validate(request.url());
        EndpointMatcher.validateFilterExpression(request.filterExpression());
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setTenantId(RequestContext.principal().tenantId());
        endpoint.setName(request.name());
        endpoint.setUrl(request.url());
        endpoint.setEncryptedSecret(secretCipher.encrypt(request.secret()));
        endpoint.setKeyVersion(secretCipher.activeVersion());
        endpoint.setSecretUpdatedAt(Instant.now());
        endpoint.setEventTypes(request.eventTypes() == null || request.eventTypes().isBlank() ? "*" : request.eventTypes());
        endpoint.setFilterExpression(request.filterExpression());
        endpoint.setActive(request.active() == null || request.active());
        endpoint.setMaxAttempts(request.maxAttempts() == null ? 5 : request.maxAttempts());
        endpoint.setRateLimitPerMinute(request.rateLimitPerMinute() == null ? 60 : request.rateLimitPerMinute());
        WebhookEndpoint saved = repository.save(endpoint);
        audit("ENDPOINT_CREATED", saved.getId());
        return EndpointResponse.from(saved);
    }

    @PutMapping("/{id}")
    public EndpointResponse update(@PathVariable Long id, @Valid @RequestBody CreateEndpointRequest request) {
        urlValidator.validate(request.url());
        EndpointMatcher.validateFilterExpression(request.filterExpression());
        WebhookEndpoint endpoint = repository.findByIdAndTenantId(id, RequestContext.principal().tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Endpoint not found: " + id));
        endpoint.setTenantId(RequestContext.principal().tenantId());
        endpoint.setName(request.name());
        endpoint.setUrl(request.url());
        endpoint.setEncryptedSecret(secretCipher.encrypt(request.secret()));
        endpoint.setKeyVersion(secretCipher.activeVersion());
        endpoint.setSecretUpdatedAt(Instant.now());
        endpoint.setEventTypes(request.eventTypes() == null || request.eventTypes().isBlank() ? "*" : request.eventTypes());
        endpoint.setFilterExpression(request.filterExpression());
        endpoint.setActive(request.active() == null || request.active());
        endpoint.setMaxAttempts(request.maxAttempts() == null ? endpoint.getMaxAttempts() : request.maxAttempts());
        endpoint.setRateLimitPerMinute(request.rateLimitPerMinute() == null ? endpoint.getRateLimitPerMinute() : request.rateLimitPerMinute());
        WebhookEndpoint saved = repository.save(endpoint);
        audit("ENDPOINT_UPDATED", saved.getId());
        return EndpointResponse.from(saved);
    }

    @PostMapping("/{id}/pause")
    public EndpointResponse pause(@PathVariable Long id) {
        WebhookEndpoint endpoint = owned(id);
        endpoint.setPausedAt(Instant.now());
        endpoint.setActive(false);
        WebhookEndpoint saved = repository.save(endpoint);
        audit("ENDPOINT_PAUSED", id);
        return EndpointResponse.from(saved);
    }

    @PostMapping("/{id}/resume")
    public EndpointResponse resume(@PathVariable Long id) {
        WebhookEndpoint endpoint = owned(id);
        endpoint.setPausedAt(null);
        endpoint.setCircuitOpenUntil(null);
        endpoint.setConsecutiveFailures(0);
        endpoint.setActive(true);
        WebhookEndpoint saved = repository.save(endpoint);
        audit("ENDPOINT_RESUMED", id);
        return EndpointResponse.from(saved);
    }

    @PutMapping("/{id}/policy")
    public EndpointResponse updatePolicy(@PathVariable Long id,
                                         @Valid @RequestBody UpdateEndpointPolicyRequest request) {
        WebhookEndpoint endpoint = owned(id);
        endpoint.setMaxConcurrency(request.maxConcurrency());
        endpoint.setFailureThreshold(request.failureThreshold());
        endpoint.setCircuitCooldownSeconds(request.circuitCooldownSeconds());
        WebhookEndpoint saved = repository.save(endpoint);
        audit("ENDPOINT_POLICY_UPDATED", id);
        return EndpointResponse.from(saved);
    }

    private WebhookEndpoint owned(Long id) {
        return repository.findByIdAndTenantId(id, RequestContext.principal().tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Endpoint not found: " + id));
    }

    private void audit(String action, Long id) {
        if (audit != null) audit.record(action, "WEBHOOK_ENDPOINT", String.valueOf(id), "SUCCESS", null);
    }
}

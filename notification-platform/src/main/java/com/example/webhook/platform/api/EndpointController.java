package com.example.webhook.platform.api;

import com.example.webhook.platform.api.dto.CreateEndpointRequest;
import com.example.webhook.platform.api.dto.EndpointResponse;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import com.example.webhook.platform.security.RequestContext;
import com.example.webhook.platform.service.WebhookUrlValidator;
import com.example.webhook.platform.security.WebhookSecretCipher;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/endpoints")
public class EndpointController {
    private final WebhookEndpointRepository repository;
    private final WebhookUrlValidator urlValidator;
    private final WebhookSecretCipher secretCipher;

    public EndpointController(WebhookEndpointRepository repository, WebhookUrlValidator urlValidator,
                              WebhookSecretCipher secretCipher) {
        this.repository = repository;
        this.urlValidator = urlValidator;
        this.secretCipher = secretCipher;
    }

    @GetMapping
    public List<EndpointResponse> list() {
        return repository.findByTenantId(RequestContext.principal().tenantId()).stream().map(EndpointResponse::from).toList();
    }

    @PostMapping
    public EndpointResponse create(@Valid @RequestBody CreateEndpointRequest request) {
        urlValidator.validate(request.url());
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setTenantId(RequestContext.principal().tenantId());
        endpoint.setName(request.name());
        endpoint.setUrl(request.url());
        endpoint.setEncryptedSecret(secretCipher.encrypt(request.secret()));
        endpoint.setEventTypes(request.eventTypes() == null || request.eventTypes().isBlank() ? "*" : request.eventTypes());
        endpoint.setActive(request.active() == null || request.active());
        endpoint.setMaxAttempts(request.maxAttempts() == null ? 5 : request.maxAttempts());
        endpoint.setRateLimitPerMinute(request.rateLimitPerMinute() == null ? 60 : request.rateLimitPerMinute());
        return EndpointResponse.from(repository.save(endpoint));
    }

    @PutMapping("/{id}")
    public EndpointResponse update(@PathVariable Long id, @Valid @RequestBody CreateEndpointRequest request) {
        urlValidator.validate(request.url());
        WebhookEndpoint endpoint = repository.findByIdAndTenantId(id, RequestContext.principal().tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Endpoint not found: " + id));
        endpoint.setTenantId(RequestContext.principal().tenantId());
        endpoint.setName(request.name());
        endpoint.setUrl(request.url());
        endpoint.setEncryptedSecret(secretCipher.encrypt(request.secret()));
        endpoint.setEventTypes(request.eventTypes() == null || request.eventTypes().isBlank() ? "*" : request.eventTypes());
        endpoint.setActive(request.active() == null || request.active());
        endpoint.setMaxAttempts(request.maxAttempts() == null ? endpoint.getMaxAttempts() : request.maxAttempts());
        endpoint.setRateLimitPerMinute(request.rateLimitPerMinute() == null ? endpoint.getRateLimitPerMinute() : request.rateLimitPerMinute());
        return EndpointResponse.from(repository.save(endpoint));
    }
}

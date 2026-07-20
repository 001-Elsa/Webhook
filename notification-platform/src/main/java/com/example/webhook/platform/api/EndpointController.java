package com.example.webhook.platform.api;

import com.example.webhook.platform.api.dto.CreateEndpointRequest;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import com.example.webhook.platform.security.RequestContext;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/endpoints")
public class EndpointController {
    private final WebhookEndpointRepository repository;

    public EndpointController(WebhookEndpointRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<WebhookEndpoint> list() {
        return repository.findAll();
    }

    @PostMapping
    public WebhookEndpoint create(@Valid @RequestBody CreateEndpointRequest request) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setTenantId(RequestContext.principal().tenantId());
        endpoint.setName(request.name());
        endpoint.setUrl(request.url());
        endpoint.setSecret(request.secret());
        endpoint.setEventTypes(request.eventTypes() == null || request.eventTypes().isBlank() ? "*" : request.eventTypes());
        endpoint.setActive(request.active() == null || request.active());
        endpoint.setMaxAttempts(request.maxAttempts() == null ? 5 : request.maxAttempts());
        endpoint.setRateLimitPerMinute(request.rateLimitPerMinute() == null ? 60 : request.rateLimitPerMinute());
        return repository.save(endpoint);
    }

    @PutMapping("/{id}")
    public WebhookEndpoint update(@PathVariable Long id, @Valid @RequestBody CreateEndpointRequest request) {
        WebhookEndpoint endpoint = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Endpoint not found: " + id));
        endpoint.setTenantId(RequestContext.principal().tenantId());
        endpoint.setName(request.name());
        endpoint.setUrl(request.url());
        endpoint.setSecret(request.secret());
        endpoint.setEventTypes(request.eventTypes() == null || request.eventTypes().isBlank() ? "*" : request.eventTypes());
        endpoint.setActive(request.active() == null || request.active());
        endpoint.setMaxAttempts(request.maxAttempts() == null ? endpoint.getMaxAttempts() : request.maxAttempts());
        endpoint.setRateLimitPerMinute(request.rateLimitPerMinute() == null ? endpoint.getRateLimitPerMinute() : request.rateLimitPerMinute());
        return repository.save(endpoint);
    }
}

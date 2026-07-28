package com.example.webhook.platform.api;

import com.example.webhook.platform.api.dto.CreateApiKeyRequest;
import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.repo.ApiCredentialRepository;
import com.example.webhook.platform.repo.ApplicationClientRepository;
import com.example.webhook.platform.security.ApiKeyHasher;
import com.example.webhook.platform.security.RequestContext;
import com.example.webhook.platform.service.AuditService;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/clients/{appId}/keys")
public class ApiKeyController {
    private static final Set<String> ALLOWED_SCOPES =
            Set.of("event:write", "delivery:read", "delivery:replay", "endpoint:manage", "audit:read", "*");
    private final ApplicationClientRepository clients;
    private final ApiCredentialRepository credentials;
    private final ApiKeyHasher hasher;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyController(ApplicationClientRepository clients, ApiCredentialRepository credentials,
                            ApiKeyHasher hasher, AuditService audit) {
        this.clients = clients;
        this.credentials = credentials;
        this.hasher = hasher;
        this.audit = audit;
    }

    @PostMapping
    @Transactional
    public Map<String, Object> create(@PathVariable String appId, @Valid @RequestBody CreateApiKeyRequest request) {
        if (!ALLOWED_SCOPES.containsAll(request.scopes())) {
            throw new IllegalArgumentException("Unknown API scope");
        }
        ApplicationClient client = clients.findByAppIdAndTenantIdAndActiveTrue(
                appId, RequestContext.principal().tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + appId));
        byte[] secretBytes = new byte[32];
        random.nextBytes(secretBytes);
        String secret = "ers_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
        ApiCredential credential = new ApiCredential();
        credential.setClient(client);
        credential.setKeyId("erk_" + UUID.randomUUID().toString().replace("-", ""));
        credential.setApiKeyHash(hasher.hash(secret));
        credential.setScopes(String.join(",", request.scopes()));
        credential.setExpiresAt(request.expiresAt());
        credentials.save(credential);
        audit.record("API_KEY_CREATED", "API_CREDENTIAL", credential.getKeyId(), "SUCCESS",
                "{\"expiresAt\":\"" + request.expiresAt() + "\"}");
        return Map.of("id", credential.getId(), "keyId", credential.getKeyId(), "apiKey", secret,
                "expiresAt", request.expiresAt() == null ? "never" : request.expiresAt());
    }

    @DeleteMapping("/{id}")
    @Transactional
    public Map<String, Object> revoke(@PathVariable String appId, @PathVariable Long id) {
        ApiCredential credential = credentials.findByIdAndClientTenantId(id, RequestContext.principal().tenantId())
                .orElseThrow(() -> new IllegalArgumentException("API key not found: " + id));
        if (!credential.getClient().getAppId().equals(appId)) throw new IllegalArgumentException("API key not found");
        credential.setStatus(ApiCredentialStatus.REVOKED);
        credential.setRevokedAt(Instant.now());
        credentials.save(credential);
        audit.record("API_KEY_REVOKED", "API_CREDENTIAL", credential.getKeyId(), "SUCCESS", null);
        return Map.of("revoked", true, "keyId", credential.getKeyId());
    }
}

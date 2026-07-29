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
import java.util.LinkedHashMap;
import java.util.List;
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

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable String appId) {
        clients.findByAppIdAndTenantIdAndActiveTrue(appId, RequestContext.principal().tenantId())
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + appId));
        return credentials.findByClientAppIdAndClientTenantIdOrderByCreatedAtDesc(
                        appId, RequestContext.principal().tenantId())
                .stream()
                .map(this::redacted)
                .toList();
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

    private Map<String, Object> redacted(ApiCredential credential) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", credential.getId());
        row.put("keyId", credential.getKeyId());
        row.put("scopes", credential.getScopes());
        row.put("status", credential.getStatus().name());
        row.put("expiresAt", credential.getExpiresAt());
        row.put("revokedAt", credential.getRevokedAt());
        row.put("lastUsedAt", credential.getLastUsedAt());
        row.put("lastUsedIp", credential.getLastUsedIp());
        row.put("createdAt", credential.getCreatedAt());
        row.put("apiKeyHash", redactedHash(credential.getApiKeyHash()));
        return row;
    }

    private String redactedHash(String hash) {
        if (hash == null || hash.length() < 8) return "********";
        return hash.substring(0, 4) + "..." + hash.substring(hash.length() - 4);
    }
}

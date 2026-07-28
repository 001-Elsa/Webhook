package com.example.webhook.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "api_credentials")
public class ApiCredential {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "client_id")
    private ApplicationClient client;
    @Column(nullable = false, unique = true, length = 80)
    private String keyId;
    @Column(name = "api_key_hash", nullable = false, length = 200)
    private String apiKeyHash;
    @Column(nullable = false, length = 500)
    private String scopes;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private ApiCredentialStatus status = ApiCredentialStatus.ACTIVE;
    private Instant expiresAt;
    private Instant lastUsedAt;
    @Column(length = 64)
    private String lastUsedIp;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    private Instant revokedAt;

    public Long getId() { return id; }
    public ApplicationClient getClient() { return client; }
    public void setClient(ApplicationClient client) { this.client = client; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }
    public ApiCredentialStatus getStatus() { return status; }
    public void setStatus(ApiCredentialStatus status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(Instant lastUsedAt) { this.lastUsedAt = lastUsedAt; }
    public String getLastUsedIp() { return lastUsedIp; }
    public void setLastUsedIp(String lastUsedIp) { this.lastUsedIp = lastUsedIp; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public boolean isUsableAt(Instant now) {
        return status == ApiCredentialStatus.ACTIVE && (expiresAt == null || expiresAt.isAfter(now));
    }
}

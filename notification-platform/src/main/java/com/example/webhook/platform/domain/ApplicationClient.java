package com.example.webhook.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "application_clients", uniqueConstraints = @UniqueConstraint(columnNames = "appId"))
public class ApplicationClient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 80)
    private String tenantId;
    @Column(nullable = false, length = 80)
    private String appId;
    @Column(name = "api_key_hash", nullable = false, length = 200)
    private String apiKeyHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ClientRole role = ClientRole.PRODUCER;
    @Column(nullable = false)
    private boolean active = true;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getApiKeyHash() { return apiKeyHash; }
    public void setApiKeyHash(String apiKeyHash) { this.apiKeyHash = apiKeyHash; }
    public ClientRole getRole() { return role; }
    public void setRole(ClientRole role) { this.role = role; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
}

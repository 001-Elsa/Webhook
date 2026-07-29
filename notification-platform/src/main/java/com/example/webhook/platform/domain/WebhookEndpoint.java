package com.example.webhook.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 80)
    private String tenantId = "demo-tenant";
    @Column(nullable = false, length = 80)
    private String name;
    @Column(nullable = false, length = 500)
    private String url;
    @Column(name = "secret_encrypted", nullable = false, length = 500)
    private String encryptedSecret;
    @Column(nullable = false)
    private int keyVersion = 1;
    @Column(nullable = false, length = 40)
    private String encryptionAlgorithm = "AES-256-GCM";
    @Column(nullable = false)
    private Instant secretUpdatedAt = Instant.now();
    @Column(nullable = false, length = 500)
    private String eventTypes = "*";
    @Column(length = 500)
    private String filterExpression;
    @Column(nullable = false)
    private boolean active = true;
    @Column(nullable = false)
    private int maxAttempts = 5;
    @Column(nullable = false)
    private int rateLimitPerMinute = 60;
    @Column(nullable = false)
    private int maxConcurrency = 8;
    @Column(nullable = false)
    private int failureThreshold = 5;
    @Column(nullable = false)
    private int circuitCooldownSeconds = 60;
    @Column(nullable = false)
    private int consecutiveFailures;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CircuitState circuitState = CircuitState.CLOSED;
    @Column(nullable = false)
    private int halfOpenProbes;
    @Column(nullable = false)
    private int halfOpenMaxProbes = 1;
    private Instant circuitOpenUntil;
    private Instant pausedAt;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getEncryptedSecret() { return encryptedSecret; }
    public void setEncryptedSecret(String encryptedSecret) { this.encryptedSecret = encryptedSecret; }
    public int getKeyVersion() { return keyVersion; }
    public void setKeyVersion(int keyVersion) { this.keyVersion = keyVersion; }
    public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
    public void setEncryptionAlgorithm(String encryptionAlgorithm) { this.encryptionAlgorithm = encryptionAlgorithm; }
    public Instant getSecretUpdatedAt() { return secretUpdatedAt; }
    public void setSecretUpdatedAt(Instant secretUpdatedAt) { this.secretUpdatedAt = secretUpdatedAt; }
    public String getEventTypes() { return eventTypes; }
    public void setEventTypes(String eventTypes) { this.eventTypes = eventTypes; }
    public String getFilterExpression() { return filterExpression; }
    public void setFilterExpression(String filterExpression) { this.filterExpression = filterExpression; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    public int getRateLimitPerMinute() { return rateLimitPerMinute; }
    public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }
    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    public int getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(int failureThreshold) { this.failureThreshold = failureThreshold; }
    public int getCircuitCooldownSeconds() { return circuitCooldownSeconds; }
    public void setCircuitCooldownSeconds(int circuitCooldownSeconds) { this.circuitCooldownSeconds = circuitCooldownSeconds; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public CircuitState getCircuitState() { return circuitState; }
    public void setCircuitState(CircuitState circuitState) { this.circuitState = circuitState; }
    public int getHalfOpenProbes() { return halfOpenProbes; }
    public void setHalfOpenProbes(int halfOpenProbes) { this.halfOpenProbes = halfOpenProbes; }
    public int getHalfOpenMaxProbes() { return halfOpenMaxProbes; }
    public void setHalfOpenMaxProbes(int halfOpenMaxProbes) { this.halfOpenMaxProbes = halfOpenMaxProbes; }
    public Instant getCircuitOpenUntil() { return circuitOpenUntil; }
    public void setCircuitOpenUntil(Instant circuitOpenUntil) { this.circuitOpenUntil = circuitOpenUntil; }
    public Instant getPausedAt() { return pausedAt; }
    public void setPausedAt(Instant pausedAt) { this.pausedAt = pausedAt; }
    public Instant getCreatedAt() { return createdAt; }
}

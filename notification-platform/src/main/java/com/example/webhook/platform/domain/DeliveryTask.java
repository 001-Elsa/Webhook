package com.example.webhook.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "delivery_tasks", indexes = @Index(name = "idx_delivery_due", columnList = "status,nextAttemptAt"))
public class DeliveryTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private EventRecord event;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private WebhookEndpoint endpoint;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DeliveryStatus status = DeliveryStatus.PENDING;
    @Column(nullable = false)
    private int attemptCount;
    @Column(nullable = false)
    private Instant nextAttemptAt = Instant.now();
    @Column(length = 80)
    private String lockedBy;
    private Instant lockedUntil;
    @Column(length = 1000)
    private String lastError;
    private Integer lastStatusCode;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
    @Version
    @Column(name = "lock_version")
    private long lockVersion;

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public EventRecord getEvent() { return event; }
    public void setEvent(EventRecord event) { this.event = event; }
    public WebhookEndpoint getEndpoint() { return endpoint; }
    public void setEndpoint(WebhookEndpoint endpoint) { this.endpoint = endpoint; }
    public DeliveryStatus getStatus() { return status; }
    public void setStatus(DeliveryStatus status) { this.status = status; }
    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Integer getLastStatusCode() { return lastStatusCode; }
    public void setLastStatusCode(Integer lastStatusCode) { this.lastStatusCode = lastStatusCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getLockVersion() { return lockVersion; }
}

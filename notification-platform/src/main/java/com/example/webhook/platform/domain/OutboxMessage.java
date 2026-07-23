package com.example.webhook.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "outbox_messages", indexes = @Index(name = "idx_outbox_due", columnList = "status,nextAttemptAt"))
public class OutboxMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long deliveryId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private OutboxMessageType messageType;
    @Column(nullable = false)
    private int attemptNo;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private OutboxStatus status = OutboxStatus.PENDING;
    @Column(nullable = false)
    private int publishAttempts;
    @Column(nullable = false)
    private Instant nextAttemptAt = Instant.now();
    @Column(length = 80)
    private String lockedBy;
    private Instant lockedUntil;
    @Column(length = 1000)
    private String lastError;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate void touch() { updatedAt = Instant.now(); }
    public Long getId() { return id; }
    public Long getDeliveryId() { return deliveryId; }
    public void setDeliveryId(Long deliveryId) { this.deliveryId = deliveryId; }
    public OutboxMessageType getMessageType() { return messageType; }
    public void setMessageType(OutboxMessageType messageType) { this.messageType = messageType; }
    public int getAttemptNo() { return attemptNo; }
    public void setAttemptNo(int attemptNo) { this.attemptNo = attemptNo; }
    public OutboxStatus getStatus() { return status; }
    public void setStatus(OutboxStatus status) { this.status = status; }
    public int getPublishAttempts() { return publishAttempts; }
    public void setPublishAttempts(int publishAttempts) { this.publishAttempts = publishAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(Instant nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }
    public Instant getLockedUntil() { return lockedUntil; }
    public void setLockedUntil(Instant lockedUntil) { this.lockedUntil = lockedUntil; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}

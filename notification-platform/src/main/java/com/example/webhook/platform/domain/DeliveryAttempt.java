package com.example.webhook.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "delivery_attempts")
public class DeliveryAttempt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private DeliveryTask delivery;
    @Column(nullable = false)
    private int attemptNo;
    @Column(nullable = false)
    private boolean success;
    private Integer statusCode;
    @Column(length = 2000)
    private String responseBody;
    @Column(length = 1000)
    private String errorMessage;
    @Column(nullable = false)
    private long durationMs;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public DeliveryTask getDelivery() { return delivery; }
    public void setDelivery(DeliveryTask delivery) { this.delivery = delivery; }
    public int getAttemptNo() { return attemptNo; }
    public void setAttemptNo(int attemptNo) { this.attemptNo = attemptNo; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public Instant getCreatedAt() { return createdAt; }
}

package com.example.webhook.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "event_records", uniqueConstraints = @UniqueConstraint(name = "uk_event_tenant_id", columnNames = {"tenantId", "eventId"}),
        indexes = @Index(name = "idx_event_created", columnList = "createdAt"))
public class EventRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(length = 80)
    private String tenantId = "demo-tenant";
    @Column(length = 80)
    private String appId = "demo-order-service";
    @Column(nullable = false, length = 80)
    private String eventId;
    @Column(nullable = false, length = 120)
    private String type;
    @Column(length = 80)
    private String traceId;
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String payload;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EventStatus status = EventStatus.RECEIVED;
    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public EventStatus getStatus() { return status; }
    public void setStatus(EventStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
}

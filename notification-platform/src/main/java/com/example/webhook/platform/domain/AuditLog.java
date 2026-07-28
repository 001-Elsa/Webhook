package com.example.webhook.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "audit_logs")
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 80) private String tenantId;
    @Column(nullable = false, length = 80) private String actorId;
    @Column(nullable = false, length = 120) private String action;
    @Column(nullable = false, length = 80) private String resourceType;
    @Column(length = 120) private String resourceId;
    @Column(length = 64) private String sourceIp;
    @Column(length = 80) private String traceId;
    @Column(nullable = false, length = 30) private String outcome;
    @Column(columnDefinition = "TEXT") private String detailsJson;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResourceType() { return resourceType; }
    public void setResourceType(String resourceType) { this.resourceType = resourceType; }
    public String getResourceId() { return resourceId; }
    public void setResourceId(String resourceId) { this.resourceId = resourceId; }
    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }
    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getDetailsJson() { return detailsJson; }
    public void setDetailsJson(String detailsJson) { this.detailsJson = detailsJson; }
    public Instant getCreatedAt() { return createdAt; }
}

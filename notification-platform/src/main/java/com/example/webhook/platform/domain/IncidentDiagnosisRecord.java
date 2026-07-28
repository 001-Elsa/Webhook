package com.example.webhook.platform.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "incident_diagnoses")
public class IncidentDiagnosisRecord {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 80) private String tenantId;
    @Column(nullable = false) private Long deliveryId;
    @Column(nullable = false, length = 80) private String category;
    @Column(nullable = false) private double confidence;
    @Column(nullable = false, length = 1000) private String summary;
    @Column(nullable = false, columnDefinition = "TEXT") private String evidenceJson;
    @Column(nullable = false, columnDefinition = "TEXT") private String actionsJson;
    @Column(nullable = false) private boolean replayRecommended;
    @Column(nullable = false, length = 80) private String analyzer;
    @Column(length = 120) private String model;
    @Column(length = 80) private String promptVersion;
    @Column(nullable = false, updatable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Long getDeliveryId() { return deliveryId; }
    public void setDeliveryId(Long deliveryId) { this.deliveryId = deliveryId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public String getActionsJson() { return actionsJson; }
    public void setActionsJson(String actionsJson) { this.actionsJson = actionsJson; }
    public boolean isReplayRecommended() { return replayRecommended; }
    public void setReplayRecommended(boolean replayRecommended) { this.replayRecommended = replayRecommended; }
    public String getAnalyzer() { return analyzer; }
    public void setAnalyzer(String analyzer) { this.analyzer = analyzer; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public Instant getCreatedAt() { return createdAt; }
}

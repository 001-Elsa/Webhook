package com.example.webhook.platform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "tenant_quotas")
public class TenantQuota {
    @Id @Column(length = 80)
    private String tenantId;
    private int ingressPerSecond = 100;
    private long maxPendingDeliveries = 100_000;
    private int maxConcurrentDeliveries = 32;
    private long dailyEventLimit = 1_000_000;
    /** Daily payload ingress traffic budget (not durable storage). */
    @Column(name = "payload_storage_bytes")
    private long dailyPayloadBytes = 1_073_741_824L;
    private int schedulingWeight = 1;
    private int attemptRetentionDays = 30;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getIngressPerSecond() { return ingressPerSecond; }
    public void setIngressPerSecond(int ingressPerSecond) { this.ingressPerSecond = ingressPerSecond; }
    public long getMaxPendingDeliveries() { return maxPendingDeliveries; }
    public void setMaxPendingDeliveries(long maxPendingDeliveries) { this.maxPendingDeliveries = maxPendingDeliveries; }
    public int getMaxConcurrentDeliveries() { return maxConcurrentDeliveries; }
    public void setMaxConcurrentDeliveries(int maxConcurrentDeliveries) { this.maxConcurrentDeliveries = maxConcurrentDeliveries; }
    public long getDailyEventLimit() { return dailyEventLimit; }
    public void setDailyEventLimit(long dailyEventLimit) { this.dailyEventLimit = dailyEventLimit; }
    public long getDailyPayloadBytes() { return dailyPayloadBytes; }
    public void setDailyPayloadBytes(long dailyPayloadBytes) { this.dailyPayloadBytes = dailyPayloadBytes; }
    public int getSchedulingWeight() { return schedulingWeight; }
    public void setSchedulingWeight(int schedulingWeight) { this.schedulingWeight = schedulingWeight; }
    public int getAttemptRetentionDays() { return attemptRetentionDays; }
    public void setAttemptRetentionDays(int attemptRetentionDays) { this.attemptRetentionDays = attemptRetentionDays; }
}

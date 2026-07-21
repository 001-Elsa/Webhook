package com.example.webhook.platform.service;

import com.example.webhook.platform.repo.DeliveryAttemptRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class DeliveryLogRetentionService {
    private final DeliveryAttemptRepository attempts;
    private final MeterRegistry metrics;
    private final int retentionDays;

    public DeliveryLogRetentionService(DeliveryAttemptRepository attempts, MeterRegistry metrics,
            @Value("${webhook.retention.delivery-attempt-days:30}") int retentionDays) {
        this.attempts = attempts;
        this.metrics = metrics;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${webhook.retention.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void cleanup() {
        int deleted = attempts.deleteOlderThan(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        metrics.counter("webhook.delivery.attempts.deleted").increment(deleted);
    }
}

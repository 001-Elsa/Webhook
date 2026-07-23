package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.OutboxStatus;
import com.example.webhook.platform.repo.OutboxMessageRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
public class OutboxRetentionService {
    private final OutboxMessageRepository repository;
    private final MeterRegistry metrics;
    private final int retentionDays;

    public OutboxRetentionService(OutboxMessageRepository repository, MeterRegistry metrics,
                                  @Value("${webhook.retention.outbox-days:7}") int retentionDays) {
        this.repository = repository;
        this.metrics = metrics;
        this.retentionDays = retentionDays;
    }

    @Scheduled(cron = "${webhook.retention.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void cleanup() {
        int deleted = repository.deletePublishedBefore(OutboxStatus.PUBLISHED,
                Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        metrics.counter("webhook.outbox.deleted").increment(deleted);
    }
}

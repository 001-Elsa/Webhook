package com.example.webhook.platform.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@ConditionalOnProperty(name = "eventrelay.roles.scheduler", havingValue = "true", matchIfMissing = true)
public class DeliveryLogRetentionService {
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final int retentionDays;
    private final int batchSize;

    public DeliveryLogRetentionService(JdbcTemplate jdbc, MeterRegistry metrics,
            @Value("${webhook.retention.delivery-attempt-days:30}") int retentionDays,
            @Value("${webhook.retention.archive-batch-size:1000}") int batchSize) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
    }

    /** Archives first and deletes only IDs already present in the archive table. */
    @Scheduled(cron = "${webhook.retention.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void archive() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        int archived = jdbc.update("""
                insert ignore into delivery_attempt_archive
                    (id, delivery_id, attempt_no, success, status_code, response_body,
                     error_message, duration_ms, created_at, archived_at)
                select id, delivery_id, attempt_no, success, status_code, response_body,
                       error_message, duration_ms, created_at, current_timestamp(6)
                  from delivery_attempts
                 where created_at < ?
                 order by id
                 limit ?
                """, cutoff, batchSize);
        int deleted = jdbc.update("""
                delete from delivery_attempts
                 where id in (
                   select id from delivery_attempt_archive where created_at < ? order by id limit ?
                 )
                """, cutoff, batchSize);
        metrics.counter("webhook.delivery.attempts.archived").increment(archived);
        metrics.counter("webhook.delivery.attempts.deleted.after.archive").increment(deleted);
    }
}

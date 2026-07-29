package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.EventStatus;
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

/**
 * Soft lifecycle cleanup stub: optionally hard-deletes COMPLETED (successful) events
 * older than retention after their deliveries and attempts are gone. Default off.
 */
@Service
@ConditionalOnProperty(name = "eventrelay.roles.scheduler", havingValue = "true", matchIfMissing = true)
public class HistoricalDataRetentionService {
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final boolean purgeEventsEnabled;
    private final int eventRetentionDays;
    private final int batchSize;

    public HistoricalDataRetentionService(JdbcTemplate jdbc, MeterRegistry metrics,
            @Value("${webhook.retention.purge-events-enabled:false}") boolean purgeEventsEnabled,
            @Value("${webhook.retention.event-days:90}") int eventRetentionDays,
            @Value("${webhook.retention.purge-batch-size:500}") int batchSize) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.purgeEventsEnabled = purgeEventsEnabled;
        this.eventRetentionDays = eventRetentionDays;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${webhook.retention.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void purgeCompletedEvents() {
        if (!purgeEventsEnabled) return;
        Timestamp cutoff = Timestamp.from(Instant.now().minus(eventRetentionDays, ChronoUnit.DAYS));
        // Only purge COMPLETED events with no remaining delivery_tasks (cascade-safe stub).
        int deleted = jdbc.update("""
                delete from event_records
                 where id in (
                   select id from (
                     select e.id
                       from event_records e
                      where e.status = ?
                        and e.created_at < ?
                        and not exists (select 1 from delivery_tasks d where d.event_id = e.id)
                      order by e.id
                      limit ?
                   ) aged
                 )
                """, EventStatus.COMPLETED.name(), cutoff, batchSize);
        metrics.counter("webhook.events.purged").increment(deleted);
        metrics.counter("webhook.retention.archived").increment(deleted);
    }
}

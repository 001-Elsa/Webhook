package com.example.webhook.platform.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Archives aged delivery attempts, then deletes only rows already present in the archive.
 * Archiving does not reclaim tenant_daily_usage.payload_bytes — those counters track
 * ingress acceptance per calendar day, not live blob storage of attempt payloads.
 */
@Service
@ConditionalOnProperty(name = "eventrelay.roles.scheduler", havingValue = "true", matchIfMissing = true)
public class DeliveryLogRetentionService {
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    private final int defaultRetentionDays;
    private final int batchSize;

    public DeliveryLogRetentionService(JdbcTemplate jdbc, MeterRegistry metrics,
            @Value("${webhook.retention.delivery-attempt-days:30}") int defaultRetentionDays,
            @Value("${webhook.retention.archive-batch-size:1000}") int batchSize) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.defaultRetentionDays = defaultRetentionDays;
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${webhook.retention.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void archive() {
        // TenantQuota.attempt_retention_days overrides the global default when present.
        int archived = jdbc.update("""
                insert ignore into delivery_attempt_archive
                    (id, delivery_id, attempt_no, success, status_code, response_body,
                     error_message, duration_ms, created_at, archived_at)
                select a.id, a.delivery_id, a.attempt_no, a.success, a.status_code, a.response_body,
                       a.error_message, a.duration_ms, a.created_at, current_timestamp(6)
                  from delivery_attempts a
                  join delivery_tasks d on d.id = a.delivery_id
                  join event_records e on e.id = d.event_id
                  left join tenant_quotas q on q.tenant_id = e.tenant_id
                 where a.created_at < date_sub(utc_timestamp(6),
                           interval coalesce(q.attempt_retention_days, ?) day)
                 order by a.id
                 limit ?
                """, defaultRetentionDays, batchSize);
        int deleted = jdbc.update("""
                delete from delivery_attempts
                 where id in (
                   select id from (
                     select arch.id
                       from delivery_attempt_archive arch
                       join delivery_tasks d on d.id = arch.delivery_id
                       join event_records e on e.id = d.event_id
                       left join tenant_quotas q on q.tenant_id = e.tenant_id
                      where arch.created_at < date_sub(utc_timestamp(6),
                                interval coalesce(q.attempt_retention_days, ?) day)
                      order by arch.id
                      limit ?
                   ) aged
                 )
                """, defaultRetentionDays, batchSize);
        metrics.counter("webhook.delivery.attempts.archived").increment(archived);
        metrics.counter("webhook.delivery.attempts.deleted.after.archive").increment(deleted);
    }
}

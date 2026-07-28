package com.example.webhook.platform.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class PipelineMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong oldestOutboxSeconds = new AtomicLong();
    private final AtomicLong readyDeliveries = new AtomicLong();
    private final AtomicLong deadDeliveries = new AtomicLong();

    public PipelineMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        registry.gauge("webhook.outbox.oldest.age.seconds", oldestOutboxSeconds);
        registry.gauge("webhook.delivery.ready", readyDeliveries);
        registry.gauge("webhook.delivery.dead", deadDeliveries);
    }

    @Scheduled(fixedDelayString = "${webhook.metrics.refresh-ms:5000}")
    public void refresh() {
        oldestOutboxSeconds.set(value("""
                select coalesce(timestampdiff(second, min(created_at), current_timestamp), 0)
                  from outbox_messages where status='PENDING'
                """));
        readyDeliveries.set(value("""
                select count(*) from delivery_tasks
                 where status in ('PENDING','RETRYING') and next_attempt_at <= current_timestamp
                """));
        deadDeliveries.set(value("select count(*) from delivery_tasks where status='DEAD'"));
    }

    private long value(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        return value == null ? 0 : value.longValue();
    }
}

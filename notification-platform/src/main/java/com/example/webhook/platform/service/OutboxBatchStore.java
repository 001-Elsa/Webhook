package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.OutboxMessage;
import com.example.webhook.platform.repo.OutboxMessageRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps SKIP LOCKED and the lease update in one transaction, so publisher
 * replicas claim disjoint batches without a separate coordinator.
 */
@Component
public class OutboxBatchStore {
    private final JdbcTemplate jdbc;
    private final OutboxMessageRepository repository;
    private final TenantQuotaService tenantQuotas;

    public OutboxBatchStore(JdbcTemplate jdbc, OutboxMessageRepository repository, TenantQuotaService tenantQuotas) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.tenantQuotas = tenantQuotas;
    }

    @Transactional
    public List<OutboxMessage> claimBatch(String owner, int batchSize, int maxPerTenant,
                                          Instant now, Instant lockedUntil) {
        List<String> tenants = jdbc.queryForList("""
                select e.tenant_id
                  from outbox_messages o
                  join delivery_tasks d on d.id = o.delivery_id
                  join event_records e on e.id = d.event_id
                 where o.status = 'PENDING'
                   and o.next_attempt_at <= ?
                   and (o.locked_until is null or o.locked_until < ?)
                 group by e.tenant_id
                 order by min(o.next_attempt_at), min(o.id)
                 limit ?
                """, String.class, Timestamp.from(now), Timestamp.from(now), batchSize);
        if (tenants.isEmpty()) return List.of();
        int totalWeight = 0;
        int[] weights = new int[tenants.size()];
        for (int i = 0; i < tenants.size(); i++) {
            weights[i] = Math.max(1, tenantQuotas.schedulingWeight(tenants.get(i)));
            totalWeight += weights[i];
        }
        List<Long> ids = new ArrayList<>(batchSize);
        for (int i = 0; i < tenants.size(); i++) {
            int remaining = batchSize - ids.size();
            if (remaining == 0) break;
            int fairShare = Math.max(1, Math.min(maxPerTenant,
                    (batchSize * weights[i] + totalWeight - 1) / totalWeight));
            ids.addAll(jdbc.queryForList("""
                    select o.id
                      from outbox_messages o
                      join delivery_tasks d on d.id = o.delivery_id
                      join event_records e on e.id = d.event_id
                     where e.tenant_id = ?
                       and o.status = 'PENDING'
                       and o.next_attempt_at <= ?
                       and (o.locked_until is null or o.locked_until < ?)
                     order by o.next_attempt_at, o.id
                     limit ?
                     for update skip locked
                    """, Long.class, tenants.get(i), Timestamp.from(now), Timestamp.from(now),
                    Math.min(fairShare, remaining)));
        }
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(owner);
        args.add(Timestamp.from(lockedUntil));
        args.addAll(ids);
        jdbc.update("update outbox_messages set locked_by=?, locked_until=? where id in (" + placeholders + ")",
                args.toArray());
        return repository.findAllById(ids);
    }

    @Transactional
    public void saveResults(List<PublishResult> results, Instant now) {
        jdbc.batchUpdate("""
                update outbox_messages
                   set status=?, publish_attempts=?, next_attempt_at=?, locked_by=null,
                       locked_until=null, last_error=?, updated_at=?
                 where id=?
                """, results, results.size(), (statement, result) -> {
            statement.setString(1, result.published() ? "PUBLISHED" : "PENDING");
            statement.setInt(2, result.publishAttempts());
            statement.setTimestamp(3, Timestamp.from(result.nextAttemptAt()));
            statement.setString(4, result.error());
            statement.setTimestamp(5, Timestamp.from(now));
            statement.setLong(6, result.id());
        });
    }

    public record PublishResult(long id, boolean published, int publishAttempts,
                                Instant nextAttemptAt, String error) { }
}

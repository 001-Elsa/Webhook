package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.OutboxMessage;
import com.example.webhook.platform.repo.OutboxMessageRepository;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Keeps SKIP LOCKED claim and lease update atomic, so publisher replicas get
 * disjoint batches. Concurrent claims can still hit MySQL deadlocks on secondary
 * indexes; those are retried in a fresh transaction.
 */
@Component
public class OutboxBatchStore {
    private static final int MAX_DEADLOCK_RETRIES = 5;

    private final JdbcTemplate jdbc;
    private final OutboxMessageRepository repository;
    private final TenantQuotaService tenantQuotas;
    private final TransactionTemplate transactions;

    public OutboxBatchStore(JdbcTemplate jdbc, OutboxMessageRepository repository,
                            TenantQuotaService tenantQuotas, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.tenantQuotas = tenantQuotas;
        this.transactions = transactions;
    }

    public List<OutboxMessage> claimBatch(String owner, int batchSize, int maxPerTenant,
                                          Instant now, Instant lockedUntil) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_DEADLOCK_RETRIES; attempt++) {
            try {
                return transactions.execute(status ->
                        claimBatchOnce(owner, batchSize, maxPerTenant, now, lockedUntil));
            } catch (CannotAcquireLockException | DeadlockLoserDataAccessException deadlock) {
                last = deadlock;
            }
        }
        throw last;
    }

    private List<OutboxMessage> claimBatchOnce(String owner, int batchSize, int maxPerTenant,
                                               Instant now, Instant lockedUntil) {
        Timestamp due = Timestamp.from(now);
        Timestamp lease = Timestamp.from(lockedUntil);
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
                """, String.class, due, due, batchSize);
        if (tenants.isEmpty()) return List.of();

        int totalWeight = 0;
        int[] weights = new int[tenants.size()];
        for (int i = 0; i < tenants.size(); i++) {
            weights[i] = Math.max(1, tenantQuotas.schedulingWeight(tenants.get(i)));
            totalWeight += weights[i];
        }

        int claimed = 0;
        for (int i = 0; i < tenants.size(); i++) {
            int remaining = batchSize - claimed;
            if (remaining == 0) break;
            int fairShare = Math.max(1, Math.min(maxPerTenant,
                    (batchSize * weights[i] + totalWeight - 1) / totalWeight));
            int limit = Math.min(fairShare, remaining);
            // Lease claimable rows in one statement so concurrent publishers never
            // hold row locks across a separate SELECT then UPDATE window.
            claimed += jdbc.update("""
                    update outbox_messages target_row
                       join (
                            select claimable.id
                              from (
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
                              ) claimable
                       ) selected on selected.id = target_row.id
                       set target_row.locked_by = ?, target_row.locked_until = ?
                    """, tenants.get(i), due, due, limit, owner, lease);
        }
        if (claimed == 0) return List.of();
        List<Long> leased = jdbc.queryForList("""
                select id
                  from outbox_messages
                 where locked_by = ?
                   and locked_until = ?
                   and status = 'PENDING'
                 order by id
                 limit ?
                """, Long.class, owner, lease, batchSize);
        return repository.findAllById(leased);
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

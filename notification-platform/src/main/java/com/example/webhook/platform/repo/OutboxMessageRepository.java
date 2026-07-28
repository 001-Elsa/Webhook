package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.OutboxMessage;
import com.example.webhook.platform.domain.OutboxStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.Query;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {
    List<OutboxMessage> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            OutboxStatus status, Instant now, Pageable pageable);

    @Modifying
    @Query("""
            update OutboxMessage o set o.lockedBy = :owner, o.lockedUntil = :lockedUntil
             where o.id = :id and o.status = :status and o.nextAttemptAt <= :now
               and (o.lockedUntil is null or o.lockedUntil < :now)
            """)
    int claim(@Param("id") Long id, @Param("status") OutboxStatus status, @Param("now") Instant now,
              @Param("owner") String owner, @Param("lockedUntil") Instant lockedUntil);

    long countByStatus(OutboxStatus status);

    /**
     * MySQL INSERT IGNORE makes recovery enqueue idempotent across scanner instances.
     * The partial-equivalent recovery unique key is introduced by V5.
     */
    @Modifying
    @Query(value = """
            insert ignore into outbox_messages
                (delivery_id, message_type, attempt_no, status, publish_attempts, next_attempt_at,
                 logical_partition, created_at, updated_at)
            values (:deliveryId, :messageType, :attemptNo, 'PENDING', 0, current_timestamp(6),
                    mod(:deliveryId, 16), current_timestamp(6), current_timestamp(6))
            """, nativeQuery = true)
    int addRecoveryIfAbsent(@Param("deliveryId") Long deliveryId,
                            @Param("messageType") String messageType,
                            @Param("attemptNo") int attemptNo);

    @Modifying
    @Query("delete from OutboxMessage o where o.status = :status and o.updatedAt < :cutoff")
    int deletePublishedBefore(@Param("status") OutboxStatus status, @Param("cutoff") Instant cutoff);
}

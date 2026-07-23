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

    @Modifying
    @Query("delete from OutboxMessage o where o.status = :status and o.updatedAt < :cutoff")
    int deletePublishedBefore(@Param("status") OutboxStatus status, @Param("cutoff") Instant cutoff);
}

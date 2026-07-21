package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.DeliveryStatus;
import com.example.webhook.platform.domain.DeliveryTask;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeliveryTaskRepository extends JpaRepository<DeliveryTask, Long> {
    @EntityGraph(attributePaths = {"event", "endpoint"})
    List<DeliveryTask> findByStatusInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
            Collection<DeliveryStatus> statuses, Instant now, Pageable pageable);

    @EntityGraph(attributePaths = {"event", "endpoint"})
    List<DeliveryTask> findTop100ByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"event", "endpoint"})
    @Query("select d from DeliveryTask d where d.id = :id")
    Optional<DeliveryTask> findWithEventAndEndpointById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"event", "endpoint"})
    List<DeliveryTask> findTop100ByStatusOrderByUpdatedAtDesc(DeliveryStatus status);

    long countByStatus(DeliveryStatus status);

    long countByEventTenantIdAndEventEventId(String tenantId, String eventId);

    @Query("select count(d) from DeliveryTask d where d.status in :statuses")
    long countActive(Collection<DeliveryStatus> statuses);

    @Modifying
    @Query("""
            update DeliveryTask d
               set d.lockedBy = :owner, d.lockedUntil = :lockedUntil
             where d.id = :id
               and d.status in :statuses
               and d.nextAttemptAt <= :now
               and (d.lockedUntil is null or d.lockedUntil < :now)
            """)
    int claimDueTask(@Param("id") Long id,
                     @Param("statuses") Collection<DeliveryStatus> statuses,
                     @Param("now") Instant now,
                     @Param("owner") String owner,
                     @Param("lockedUntil") Instant lockedUntil);
}

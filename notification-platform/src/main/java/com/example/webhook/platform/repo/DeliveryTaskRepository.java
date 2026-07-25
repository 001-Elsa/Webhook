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
    List<DeliveryTask> findTop100ByEventTenantIdOrderByCreatedAtDesc(String tenantId);

    @EntityGraph(attributePaths = {"event", "endpoint"})
    @Query("select d from DeliveryTask d where d.id = :id")
    Optional<DeliveryTask> findWithEventAndEndpointById(@Param("id") Long id);

    @EntityGraph(attributePaths = {"event", "endpoint"})
    List<DeliveryTask> findTop100ByEventTenantIdAndStatusOrderByUpdatedAtDesc(String tenantId, DeliveryStatus status);

    Optional<DeliveryTask> findByIdAndEventTenantId(Long id, String tenantId);

    long countByEventTenantIdAndStatus(String tenantId, DeliveryStatus status);
    long countByEventTenantId(String tenantId);
    long countByEventIdAndStatus(Long eventId, DeliveryStatus status);

    @Query("""
            select new com.example.webhook.platform.repo.DeliveryStatusCounts(
                sum(case when d.status in :activeStatuses then 1L else 0L end),
                sum(case when d.status = :succeeded then 1L else 0L end),
                sum(case when d.status = :dead then 1L else 0L end))
              from DeliveryTask d
             where d.event.id = :eventId
            """)
    DeliveryStatusCounts countStatusesByEventId(@Param("eventId") Long eventId,
                                                  @Param("activeStatuses") Collection<DeliveryStatus> activeStatuses,
                                                  @Param("succeeded") DeliveryStatus succeeded,
                                                  @Param("dead") DeliveryStatus dead);

    long countByEventTenantIdAndEventEventId(String tenantId, String eventId);

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

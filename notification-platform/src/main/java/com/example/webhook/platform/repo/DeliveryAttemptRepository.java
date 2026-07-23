package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.DeliveryAttempt;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.time.Instant;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {
    @EntityGraph(attributePaths = {"delivery", "delivery.event", "delivery.endpoint"})
    List<DeliveryAttempt> findTop200ByDeliveryEventTenantIdOrderByCreatedAtDesc(String tenantId);

    List<DeliveryAttempt> findByDeliveryIdOrderByCreatedAtDesc(Long deliveryId);

    List<DeliveryAttempt> findByDeliveryIdAndDeliveryEventTenantIdOrderByCreatedAtDesc(Long deliveryId, String tenantId);

    @Modifying
    @Query("delete from DeliveryAttempt a where a.createdAt < :cutoff")
    int deleteOlderThan(Instant cutoff);
}

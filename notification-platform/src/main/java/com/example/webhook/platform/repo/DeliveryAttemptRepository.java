package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.DeliveryAttempt;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {
    @EntityGraph(attributePaths = {"delivery", "delivery.event", "delivery.endpoint"})
    List<DeliveryAttempt> findTop200ByOrderByCreatedAtDesc();

    List<DeliveryAttempt> findByDeliveryIdOrderByCreatedAtDesc(Long deliveryId);
}

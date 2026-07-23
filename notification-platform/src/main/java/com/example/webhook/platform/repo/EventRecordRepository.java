package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.EventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import com.example.webhook.platform.domain.EventStatus;
import java.util.Collection;

public interface EventRecordRepository extends JpaRepository<EventRecord, Long> {
    List<EventRecord> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    Optional<EventRecord> findByTenantIdAndEventId(String tenantId, String eventId);
    long countByTenantId(String tenantId);
    List<EventRecord> findByStatusInOrderByCreatedAtAsc(Collection<EventStatus> statuses, Pageable pageable);
}

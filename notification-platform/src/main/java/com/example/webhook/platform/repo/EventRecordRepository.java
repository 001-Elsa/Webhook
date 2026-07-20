package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.EventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface EventRecordRepository extends JpaRepository<EventRecord, Long> {
    Optional<EventRecord> findByEventId(String eventId);
    Optional<EventRecord> findByTenantIdAndEventId(String tenantId, String eventId);
}

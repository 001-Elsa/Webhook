package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.IncidentDiagnosisRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface IncidentDiagnosisRepository extends JpaRepository<IncidentDiagnosisRecord, Long> {
    List<IncidentDiagnosisRecord> findTop20ByTenantIdAndDeliveryIdOrderByCreatedAtDesc(String tenantId, Long deliveryId);
}

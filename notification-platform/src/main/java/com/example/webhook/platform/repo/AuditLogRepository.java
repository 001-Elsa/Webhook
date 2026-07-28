package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByTenantIdOrderByCreatedAtDesc(String tenantId, Pageable pageable);
}

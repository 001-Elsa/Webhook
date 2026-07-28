package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.ReplayJob;
import com.example.webhook.platform.domain.ReplayJobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ReplayJobRepository extends JpaRepository<ReplayJob, Long> {
    List<ReplayJob> findByStatusOrderByCreatedAtAsc(ReplayJobStatus status, Pageable pageable);
    Optional<ReplayJob> findByIdAndTenantId(Long id, String tenantId);
    List<ReplayJob> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);
}

package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.ReplayJob;
import com.example.webhook.platform.domain.ReplayJobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReplayJobRepository extends JpaRepository<ReplayJob, Long> {
    List<ReplayJob> findByStatusOrderByCreatedAtAsc(ReplayJobStatus status, Pageable pageable);
    Optional<ReplayJob> findByIdAndTenantId(Long id, String tenantId);
    List<ReplayJob> findTop100ByTenantIdOrderByCreatedAtDesc(String tenantId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
            update ReplayJob j
               set j.status = :running, j.startedAt = :startedAt
             where j.id = :id and j.status = :pending
            """)
    int claimPending(@Param("id") Long id,
                     @Param("startedAt") Instant startedAt,
                     @Param("running") ReplayJobStatus running,
                     @Param("pending") ReplayJobStatus pending);
}

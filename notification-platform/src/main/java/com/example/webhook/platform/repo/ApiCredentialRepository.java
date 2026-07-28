package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.ApiCredential;
import com.example.webhook.platform.domain.ApiCredentialStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ApiCredentialRepository extends JpaRepository<ApiCredential, Long> {
    @EntityGraph(attributePaths = "client")
    List<ApiCredential> findByClientAppIdAndStatus(String appId, ApiCredentialStatus status);
    Optional<ApiCredential> findByIdAndClientTenantId(Long id, String tenantId);
    boolean existsByKeyId(String keyId);

    @Modifying
    @Transactional
    @Query("update ApiCredential c set c.lastUsedAt=:usedAt, c.lastUsedIp=:sourceIp where c.id=:id")
    int recordUsage(@Param("id") Long id, @Param("usedAt") Instant usedAt, @Param("sourceIp") String sourceIp);
}

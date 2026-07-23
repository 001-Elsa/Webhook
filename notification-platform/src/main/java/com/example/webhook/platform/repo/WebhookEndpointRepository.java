package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {
    List<WebhookEndpoint> findByActiveTrue();
    List<WebhookEndpoint> findByTenantId(String tenantId);
    List<WebhookEndpoint> findByTenantIdAndActiveTrue(String tenantId);
    Optional<WebhookEndpoint> findByIdAndTenantId(Long id, String tenantId);
    long countByTenantId(String tenantId);
}

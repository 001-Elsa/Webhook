package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.WebhookEndpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WebhookEndpointRepository extends JpaRepository<WebhookEndpoint, Long> {
    List<WebhookEndpoint> findByActiveTrue();
    List<WebhookEndpoint> findByTenantIdAndActiveTrue(String tenantId);
}

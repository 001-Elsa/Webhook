package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.ApplicationClient;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ApplicationClientRepository extends JpaRepository<ApplicationClient, Long> {
    Optional<ApplicationClient> findByAppIdAndActiveTrue(String appId);
}

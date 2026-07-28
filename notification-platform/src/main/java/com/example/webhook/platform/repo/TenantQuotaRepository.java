package com.example.webhook.platform.repo;

import com.example.webhook.platform.domain.TenantQuota;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantQuotaRepository extends JpaRepository<TenantQuota, String> { }

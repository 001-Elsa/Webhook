package com.example.webhook.platform.api;

import com.example.webhook.platform.api.dto.UpdateTenantQuotaRequest;
import com.example.webhook.platform.domain.TenantQuota;
import com.example.webhook.platform.repo.TenantQuotaRepository;
import com.example.webhook.platform.security.RequestContext;
import com.example.webhook.platform.service.AuditService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tenant/quota")
public class TenantQuotaController {
    private final TenantQuotaRepository quotas;
    private final AuditService audit;

    public TenantQuotaController(TenantQuotaRepository quotas, AuditService audit) {
        this.quotas = quotas;
        this.audit = audit;
    }

    @GetMapping
    public TenantQuota get() {
        return quotas.findById(RequestContext.principal().tenantId()).orElseGet(() -> {
            TenantQuota quota = new TenantQuota();
            quota.setTenantId(RequestContext.principal().tenantId());
            return quota;
        });
    }

    @PutMapping
    public TenantQuota update(@Valid @RequestBody UpdateTenantQuotaRequest request) {
        TenantQuota quota = get();
        quota.setIngressPerSecond(request.ingressPerSecond());
        quota.setMaxPendingDeliveries(request.maxPendingDeliveries());
        quota.setMaxConcurrentDeliveries(request.maxConcurrentDeliveries());
        quota.setDailyEventLimit(request.dailyEventLimit());
        quota.setPayloadStorageBytes(request.payloadStorageBytes());
        quota.setSchedulingWeight(request.schedulingWeight());
        TenantQuota saved = quotas.save(quota);
        audit.record("TENANT_QUOTA_UPDATED", "TENANT_QUOTA", saved.getTenantId(), "SUCCESS", null);
        return saved;
    }
}

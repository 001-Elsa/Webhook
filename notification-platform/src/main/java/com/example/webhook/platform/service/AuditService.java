package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.AuditLog;
import com.example.webhook.platform.repo.AuditLogRepository;
import com.example.webhook.platform.security.RequestContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) { this.repository = repository; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, String outcome, String detailsJson) {
        var principal = RequestContext.principal();
        AuditLog entry = new AuditLog();
        entry.setTenantId(principal.tenantId());
        entry.setActorId(principal.appId());
        entry.setAction(action);
        entry.setResourceType(resourceType);
        entry.setResourceId(resourceId);
        entry.setTraceId(RequestContext.traceId());
        entry.setOutcome(outcome);
        entry.setDetailsJson(detailsJson);
        repository.save(entry);
    }
}

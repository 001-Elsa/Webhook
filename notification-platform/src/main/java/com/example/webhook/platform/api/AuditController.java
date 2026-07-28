package com.example.webhook.platform.api;

import com.example.webhook.platform.domain.AuditLog;
import com.example.webhook.platform.repo.AuditLogRepository;
import com.example.webhook.platform.security.RequestContext;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AuditLogRepository audit;

    public AuditController(AuditLogRepository audit) { this.audit = audit; }

    @GetMapping
    public List<AuditLog> list() {
        return audit.findByTenantIdOrderByCreatedAtDesc(
                RequestContext.principal().tenantId(), PageRequest.of(0, 200));
    }
}

package com.example.webhook.platform.security;

import com.example.webhook.platform.domain.ClientRole;
import java.util.Set;

public record ApiPrincipal(String tenantId, String appId, ClientRole role, Set<String> scopes) {
    public ApiPrincipal(String tenantId, String appId, ClientRole role) {
        this(tenantId, appId, role, role == ClientRole.ADMIN ? Set.of("*") : Set.of("event:write"));
    }

    public ApiPrincipal {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    public boolean hasRole(ClientRole expected) {
        if (role == ClientRole.ADMIN) {
            return true;
        }
        return role == expected;
    }

    public boolean hasScope(String expected) {
        return scopes.contains("*") || scopes.contains(expected);
    }
}

package com.example.webhook.platform.security;

import com.example.webhook.platform.domain.ClientRole;

public record ApiPrincipal(String tenantId, String appId, ClientRole role) {
    public boolean hasRole(ClientRole expected) {
        if (role == ClientRole.ADMIN) {
            return true;
        }
        return role == expected;
    }
}

package com.example.webhook.platform.security;

import com.example.webhook.platform.domain.ClientRole;
import java.util.Optional;
import java.util.UUID;

public final class RequestContext {
    private static final ThreadLocal<ApiPrincipal> PRINCIPAL = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private static final ApiPrincipal ANONYMOUS_DEMO =
            new ApiPrincipal("demo-tenant", "demo-order-service", ClientRole.ADMIN);

    private RequestContext() {
    }

    public static void set(ApiPrincipal principal, String traceId) {
        PRINCIPAL.set(principal);
        TRACE_ID.set(traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
    }

    public static ApiPrincipal principal() {
        return Optional.ofNullable(PRINCIPAL.get()).orElse(ANONYMOUS_DEMO);
    }

    public static String traceId() {
        return Optional.ofNullable(TRACE_ID.get()).orElseGet(() -> {
            String value = UUID.randomUUID().toString();
            TRACE_ID.set(value);
            return value;
        });
    }

    public static void clear() {
        PRINCIPAL.remove();
        TRACE_ID.remove();
    }
}

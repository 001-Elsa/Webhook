package com.example.webhook.platform.security;

import java.util.UUID;
import java.util.Optional;

public final class RequestContext {
    private static final ThreadLocal<ApiPrincipal> PRINCIPAL = new ThreadLocal<>();
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private RequestContext() {
    }

    public static void set(ApiPrincipal principal, String traceId) {
        PRINCIPAL.set(principal);
        TRACE_ID.set(traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId);
    }

    public static ApiPrincipal principal() {
        ApiPrincipal principal = PRINCIPAL.get();
        if (principal == null) {
            throw new IllegalStateException("Authenticated request context is required");
        }
        return principal;
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

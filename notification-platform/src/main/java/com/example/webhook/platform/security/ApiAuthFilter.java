package com.example.webhook.platform.security;

import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.repo.ApiCredentialRepository;
import com.example.webhook.platform.repo.ApplicationClientRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class ApiAuthFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_PREFIXES = Set.of("/actuator", "/v3/api-docs", "/swagger-ui");
    private final ApplicationClientRepository clientRepository;
    private final ApiCredentialRepository credentialRepository;
    private final ApiKeyHasher apiKeyHasher;
    private final MeterRegistry metrics;
    private final boolean legacyKeyEnabled;
    private final Instant legacyKeyDeadline;

    /** Compatibility constructor for existing focused tests. */
    public ApiAuthFilter(ApplicationClientRepository clients, ApiKeyHasher hasher, MeterRegistry metrics) {
        this(clients, null, hasher, metrics, true, "");
    }

    @Autowired
    public ApiAuthFilter(ApplicationClientRepository clients, ApiCredentialRepository credentials,
                         ApiKeyHasher hasher, MeterRegistry metrics,
                         @Value("${webhook.auth.legacy-key-enabled:true}") boolean legacyKeyEnabled,
                         @Value("${webhook.auth.legacy-key-deadline:}") String legacyKeyDeadline) {
        this.clientRepository = clients;
        this.credentialRepository = credentials;
        this.apiKeyHasher = hasher;
        this.metrics = metrics;
        this.legacyKeyEnabled = legacyKeyEnabled;
        this.legacyKeyDeadline = parseDeadline(legacyKeyDeadline);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String suppliedTrace = request.getHeader("X-Trace-Id");
        String traceId = suppliedTrace == null || !suppliedTrace.matches("[A-Za-z0-9._-]{1,80}")
                ? UUID.randomUUID().toString() : suppliedTrace;
        response.setHeader("X-Trace-Id", traceId);
        try {
            MDC.put("traceId", traceId);
            if (!request.getRequestURI().startsWith("/api")) {
                filterChain.doFilter(request, response);
                return;
            }
            Authenticated authenticated = authenticate(request, response);
            if (authenticated == null) return;
            ApplicationClient client = authenticated.client();
            ApiPrincipal principal = new ApiPrincipal(client.getTenantId(), client.getAppId(), client.getRole(),
                    authenticated.scopes());
            RequestContext.set(principal, traceId);
            if (!isAllowed(request, principal)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Insufficient scope");
                return;
            }
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
            RequestContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return PUBLIC_PREFIXES.stream().anyMatch(request.getRequestURI()::startsWith);
    }

    private Authenticated authenticate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String appId = request.getHeader("X-App-Id");
        String apiKey = request.getHeader("X-Api-Key");
        if (appId == null || apiKey == null || appId.isBlank() || apiKey.isBlank()
                || appId.length() > 80 || apiKey.length() > 512) {
            metrics.counter("webhook.auth.failure", "reason", "missing").increment();
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-App-Id or X-Api-Key");
            return null;
        }
        ApplicationClient client = clientRepository.findByAppIdAndActiveTrue(appId).orElse(null);
        if (client == null) return unauthorized(response, "invalid");

        // New credentials support overlapping active keys for zero-downtime rotation.
        if (credentialRepository != null) {
            String keyId = request.getHeader("X-Key-Id");
            for (ApiCredential credential : credentialRepository.findByClientAppIdAndStatus(
                    appId, ApiCredentialStatus.ACTIVE)) {
                if (keyId != null && !keyId.equals(credential.getKeyId())) continue;
                if (credential.isUsableAt(Instant.now())
                        && apiKeyHasher.matches(apiKey, credential.getApiKeyHash())) {
                    credentialRepository.recordUsage(credential.getId(), Instant.now(), clientIp(request));
                    return new Authenticated(client, parseScopes(credential.getScopes()));
                }
            }
        }

        // Legacy ApplicationClient.apiKeyHash path — globally gated for migration cutover.
        if (apiKeyHasher.matches(apiKey, client.getApiKeyHash())) {
            if (!isLegacyAllowed()) {
                metrics.counter("webhook.auth.legacy.rejected").increment();
                return unauthorized(response, "legacy_disabled");
            }
            metrics.counter("webhook.auth.legacy.used").increment();
            return new Authenticated(client,
                    client.getRole() == ClientRole.ADMIN ? Set.of("*") : Set.of("event:write"));
        }
        return unauthorized(response, "invalid");
    }

    private boolean isLegacyAllowed() {
        if (!legacyKeyEnabled) return false;
        return legacyKeyDeadline == null || !Instant.now().isAfter(legacyKeyDeadline);
    }

    private static Instant parseDeadline(String value) {
        if (value == null || value.isBlank()) return null;
        return Instant.parse(value.trim());
    }

    private Authenticated unauthorized(HttpServletResponse response, String reason) throws IOException {
        metrics.counter("webhook.auth.failure", "reason", reason).increment();
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired API credential");
        return null;
    }

    private boolean isAllowed(HttpServletRequest request, ApiPrincipal principal) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        if ("POST".equalsIgnoreCase(method) && "/api/events".equals(uri)) return principal.hasScope("event:write");
        if (uri.startsWith("/api/deliveries") && "GET".equalsIgnoreCase(method)) {
            return principal.hasScope("delivery:read");
        }
        if (uri.contains("/diagnosis")) return principal.hasScope("delivery:read");
        if (uri.contains("/replay") || uri.endsWith("/retry")) return principal.hasScope("delivery:replay");
        if (uri.startsWith("/api/endpoints")) return principal.hasScope("endpoint:manage");
        if (uri.startsWith("/api/audit")) return principal.hasScope("audit:read");
        if (uri.startsWith("/api/dashboard") || ("GET".equalsIgnoreCase(method) && uri.startsWith("/api/events"))) {
            return principal.hasScope("delivery:read");
        }
        return principal.role() == ClientRole.ADMIN && principal.hasScope("*");
    }

    private Set<String> parseScopes(String scopes) {
        return Arrays.stream(scopes.split("[, ]+")).filter(value -> !value.isBlank()).collect(Collectors.toSet());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String value = forwarded == null ? request.getRemoteAddr() : forwarded.split(",")[0].trim();
        return value == null || value.length() <= 64 ? value : value.substring(0, 64);
    }

    private record Authenticated(ApplicationClient client, Set<String> scopes) { }
}

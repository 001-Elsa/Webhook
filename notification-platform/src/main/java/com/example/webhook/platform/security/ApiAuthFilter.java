package com.example.webhook.platform.security;

import com.example.webhook.platform.domain.ApplicationClient;
import com.example.webhook.platform.domain.ClientRole;
import com.example.webhook.platform.repo.ApplicationClientRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.Set;

@Component
public class ApiAuthFilter extends OncePerRequestFilter {
    private static final Set<String> PUBLIC_PREFIXES = Set.of("/actuator", "/v3/api-docs", "/swagger-ui");
    private final ApplicationClientRepository clientRepository;

    public ApiAuthFilter(ApplicationClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader("X-Trace-Id");
        String effectiveTraceId = traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
        response.setHeader("X-Trace-Id", effectiveTraceId);

        try {
            MDC.put("traceId", effectiveTraceId);
            if (!request.getRequestURI().startsWith("/api") || isPublicApiRead(request)) {
                RequestContext.set(new ApiPrincipal("demo-tenant", "web-console", ClientRole.ADMIN), effectiveTraceId);
                filterChain.doFilter(request, response);
                return;
            }

            ApplicationClient client = authenticate(request, response);
            if (client == null) {
                return;
            }
            ApiPrincipal principal = new ApiPrincipal(client.getTenantId(), client.getAppId(), client.getRole());
            RequestContext.set(principal, effectiveTraceId);
            if (!isAllowed(request, principal.role())) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Insufficient role");
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
        String uri = request.getRequestURI();
        return PUBLIC_PREFIXES.stream().anyMatch(uri::startsWith) || "/".equals(uri) || uri.endsWith(".html");
    }

    private boolean isPublicApiRead(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && (request.getRequestURI().startsWith("/api/dashboard")
                || request.getRequestURI().startsWith("/api/deliveries")
                || request.getRequestURI().startsWith("/api/endpoints")
                || request.getRequestURI().startsWith("/api/events"));
    }

    private ApplicationClient authenticate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String appId = request.getHeader("X-App-Id");
        String apiKey = request.getHeader("X-Api-Key");
        if (appId == null || apiKey == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing X-App-Id or X-Api-Key");
            return null;
        }
        ApplicationClient client = clientRepository.findByAppIdAndActiveTrue(appId).orElse(null);
        if (client == null || !MessageDigest.isEqual(client.getApiKey().getBytes(), apiKey.getBytes())) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid API credential");
            return null;
        }
        return client;
    }

    private boolean isAllowed(HttpServletRequest request, ClientRole role) {
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/events")) {
            return role == ClientRole.ADMIN || role == ClientRole.PRODUCER;
        }
        return role == ClientRole.ADMIN;
    }
}

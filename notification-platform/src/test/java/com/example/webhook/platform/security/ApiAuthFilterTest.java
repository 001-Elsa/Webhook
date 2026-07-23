package com.example.webhook.platform.security;

import com.example.webhook.platform.domain.ApplicationClient;
import com.example.webhook.platform.domain.ClientRole;
import com.example.webhook.platform.repo.ApplicationClientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ApiAuthFilterTest {
    @Test
    void apiReadsRequireCredentials() throws Exception {
        ApplicationClientRepository repository = mock(ApplicationClientRepository.class);
        ApiAuthFilter filter = new ApiAuthFilter(repository, new ApiKeyHasher(), new SimpleMeterRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/deliveries");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void validHashedCredentialAuthenticatesApiRead() throws Exception {
        ApplicationClientRepository repository = mock(ApplicationClientRepository.class);
        ApiKeyHasher hasher = new ApiKeyHasher();
        ApplicationClient client = new ApplicationClient();
        client.setTenantId("tenant-a");
        client.setAppId("admin-a");
        client.setRole(ClientRole.ADMIN);
        client.setApiKeyHash(hasher.hash("secret-key"));
        when(repository.findByAppIdAndActiveTrue("admin-a")).thenReturn(Optional.of(client));
        ApiAuthFilter filter = new ApiAuthFilter(repository, hasher, new SimpleMeterRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/deliveries");
        request.addHeader("X-App-Id", "admin-a");
        request.addHeader("X-Api-Key", "secret-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void producerCannotReadAdministrativeDeliveryData() throws Exception {
        ApplicationClientRepository repository = mock(ApplicationClientRepository.class);
        ApiKeyHasher hasher = new ApiKeyHasher();
        ApplicationClient client = new ApplicationClient();
        client.setTenantId("tenant-a");
        client.setAppId("producer-a");
        client.setRole(ClientRole.PRODUCER);
        client.setApiKeyHash(hasher.hash("secret-key"));
        when(repository.findByAppIdAndActiveTrue("producer-a")).thenReturn(Optional.of(client));
        ApiAuthFilter filter = new ApiAuthFilter(repository, hasher, new SimpleMeterRegistry());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/deliveries");
        request.addHeader("X-App-Id", "producer-a");
        request.addHeader("X-Api-Key", "secret-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
    }
}

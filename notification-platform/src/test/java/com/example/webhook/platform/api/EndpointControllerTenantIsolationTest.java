package com.example.webhook.platform.api;

import com.example.webhook.platform.api.dto.CreateEndpointRequest;
import com.example.webhook.platform.domain.ClientRole;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import com.example.webhook.platform.security.ApiPrincipal;
import com.example.webhook.platform.security.RequestContext;
import com.example.webhook.platform.service.WebhookUrlValidator;
import com.example.webhook.platform.security.WebhookSecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class EndpointControllerTenantIsolationTest {
    private final WebhookEndpointRepository repository = mock(WebhookEndpointRepository.class);
    private final WebhookUrlValidator urlValidator = mock(WebhookUrlValidator.class);
    private final WebhookSecretCipher secretCipher = mock(WebhookSecretCipher.class);
    private final EndpointController controller = new EndpointController(repository, urlValidator, secretCipher);

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void listReadsOnlyCurrentTenantEndpoints() {
        RequestContext.set(new ApiPrincipal("tenant-a", "app-a", ClientRole.ADMIN), "trace-a");
        when(repository.findByTenantId("tenant-a")).thenReturn(List.of(new WebhookEndpoint()));

        controller.list();

        verify(repository).findByTenantId("tenant-a");
        verify(repository, never()).findAll();
    }

    @Test
    void endpointResponsesNeverExposeSigningSecret() throws Exception {
        RequestContext.set(new ApiPrincipal("tenant-a", "app-a", ClientRole.ADMIN), "trace-a");
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setEncryptedSecret("v1:encrypted-value");
        endpoint.setName("receiver");
        endpoint.setUrl("https://example.com/webhook");
        when(repository.findByTenantId("tenant-a")).thenReturn(List.of(endpoint));

        String json = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                .writeValueAsString(controller.list());

        assertThat(json).doesNotContain("signing-secret").doesNotContain("secret");
    }

    @Test
    void updateCannotModifyEndpointFromAnotherTenant() {
        RequestContext.set(new ApiPrincipal("tenant-a", "app-a", ClientRole.ADMIN), "trace-a");
        when(repository.findByIdAndTenantId(9L, "tenant-a")).thenReturn(Optional.empty());
        CreateEndpointRequest request = new CreateEndpointRequest("receiver", "https://example.com/webhook",
                "secret", "*", true, 5, 60);

        assertThatThrownBy(() -> controller.update(9L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Endpoint not found");

        verify(repository).findByIdAndTenantId(9L, "tenant-a");
        verify(repository, never()).findById(9L);
        verify(repository, never()).save(any());
    }
}

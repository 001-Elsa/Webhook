package com.example.webhook.platform.config;

import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.domain.ApplicationClient;
import com.example.webhook.platform.domain.ClientRole;
import com.example.webhook.platform.repo.ApplicationClientRepository;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.example.webhook.platform.security.ApiKeyHasher;
import com.example.webhook.platform.security.WebhookSecretCipher;

@Configuration
@ConditionalOnProperty(name = "webhook.demo.enabled", havingValue = "true")
public class DemoDataInitializer {
    @Bean
    CommandLineRunner seedDemoEndpoint(WebhookEndpointRepository repository,
            WebhookSecretCipher secretCipher,
            @Value("${webhook.demo.receiver-secret}") String receiverSecret,
            @Value("${webhook.demo-receiver-url:http://localhost:8082/webhook/demo-merchant}") String receiverUrl) {
        return args -> {
            if (receiverSecret.isBlank()) throw new IllegalStateException("Demo receiver secret is required");
            repository.findAll().stream()
                    .filter(endpoint -> endpoint.getTenantId() == null || endpoint.getTenantId().isBlank())
                    .forEach(endpoint -> {
                        endpoint.setTenantId("demo-tenant");
                        repository.save(endpoint);
                    });
            if (repository.count() > 0) {
                return;
            }
            WebhookEndpoint endpoint = new WebhookEndpoint();
            endpoint.setName("Demo merchant receiver");
            endpoint.setUrl(receiverUrl);
            endpoint.setEncryptedSecret(secretCipher.encrypt(receiverSecret));
            endpoint.setEventTypes("order.created,order.paid,order.cancelled,order.shipped");
            endpoint.setMaxAttempts(5);
            endpoint.setRateLimitPerMinute(60);
            repository.save(endpoint);
        };
    }

    @Bean
    CommandLineRunner seedDemoClients(ApplicationClientRepository repository, ApiKeyHasher apiKeyHasher,
            @Value("${webhook.demo.admin-api-key}") String adminApiKey,
            @Value("${webhook.demo.producer-api-key}") String producerApiKey) {
        return args -> {
            if (adminApiKey.isBlank() || producerApiKey.isBlank()) {
                throw new IllegalStateException("Demo API keys must be provided when webhook.demo.enabled=true");
            }
            var existingAdmin = repository.findByAppIdAndActiveTrue("platform-admin");
            if (existingAdmin.isEmpty()) {
                ApplicationClient admin = new ApplicationClient();
                admin.setTenantId("demo-tenant");
                admin.setAppId("platform-admin");
                admin.setApiKeyHash(apiKeyHasher.hash(adminApiKey));
                admin.setRole(ClientRole.ADMIN);
                repository.save(admin);
            } else if (!apiKeyHasher.matches(adminApiKey, existingAdmin.get().getApiKeyHash())) {
                existingAdmin.get().setApiKeyHash(apiKeyHasher.hash(adminApiKey));
                repository.save(existingAdmin.get());
            }
            var existingProducer = repository.findByAppIdAndActiveTrue("demo-order-service");
            if (existingProducer.isEmpty()) {
                ApplicationClient producer = new ApplicationClient();
                producer.setTenantId("demo-tenant");
                producer.setAppId("demo-order-service");
                producer.setApiKeyHash(apiKeyHasher.hash(producerApiKey));
                producer.setRole(ClientRole.PRODUCER);
                repository.save(producer);
            } else if (!apiKeyHasher.matches(producerApiKey, existingProducer.get().getApiKeyHash())) {
                existingProducer.get().setApiKeyHash(apiKeyHasher.hash(producerApiKey));
                repository.save(existingProducer.get());
            }
        };
    }
}

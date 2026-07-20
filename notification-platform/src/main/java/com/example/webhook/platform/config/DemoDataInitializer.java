package com.example.webhook.platform.config;

import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.domain.ApplicationClient;
import com.example.webhook.platform.domain.ClientRole;
import com.example.webhook.platform.repo.ApplicationClientRepository;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoDataInitializer {
    @Bean
    CommandLineRunner seedDemoEndpoint(WebhookEndpointRepository repository) {
        return args -> {
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
            endpoint.setUrl("http://localhost:8082/webhook/demo-merchant");
            endpoint.setSecret("demo-secret");
            endpoint.setEventTypes("order.created,order.paid,order.cancelled,order.shipped");
            endpoint.setMaxAttempts(5);
            endpoint.setRateLimitPerMinute(60);
            repository.save(endpoint);
        };
    }

    @Bean
    CommandLineRunner seedDemoClients(ApplicationClientRepository repository) {
        return args -> {
            if (repository.findByAppIdAndActiveTrue("platform-admin").isEmpty()) {
                ApplicationClient admin = new ApplicationClient();
                admin.setTenantId("demo-tenant");
                admin.setAppId("platform-admin");
                admin.setApiKey("admin-key");
                admin.setRole(ClientRole.ADMIN);
                repository.save(admin);
            }
            if (repository.findByAppIdAndActiveTrue("demo-order-service").isEmpty()) {
                ApplicationClient producer = new ApplicationClient();
                producer.setTenantId("demo-tenant");
                producer.setAppId("demo-order-service");
                producer.setApiKey("order-key");
                producer.setRole(ClientRole.PRODUCER);
                repository.save(producer);
            }
        };
    }
}

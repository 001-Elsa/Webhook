package com.example.webhook.platform.config;

import com.example.webhook.platform.repo.WebhookEndpointRepository;
import com.example.webhook.platform.security.WebhookSecretCipher;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(0)
public class SecretMigrationInitializer implements CommandLineRunner {
    private final WebhookEndpointRepository repository;
    private final WebhookSecretCipher cipher;

    public SecretMigrationInitializer(WebhookEndpointRepository repository, WebhookSecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Override @Transactional
    public void run(String... args) {
        repository.findAll().stream().filter(endpoint -> !cipher.isEncrypted(endpoint.getEncryptedSecret()))
                .forEach(endpoint -> endpoint.setEncryptedSecret(cipher.encrypt(endpoint.getEncryptedSecret())));
    }
}

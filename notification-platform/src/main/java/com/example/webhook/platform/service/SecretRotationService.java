package com.example.webhook.platform.service;

import com.example.webhook.platform.repo.WebhookEndpointRepository;
import com.example.webhook.platform.security.WebhookSecretCipher;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@ConditionalOnProperty(name = "eventrelay.roles.scheduler", havingValue = "true", matchIfMissing = true)
public class SecretRotationService {
    private final WebhookEndpointRepository endpoints;
    private final WebhookSecretCipher cipher;
    private final MeterRegistry metrics;
    private final LeaderLeaseService leases;

    public SecretRotationService(WebhookEndpointRepository endpoints, WebhookSecretCipher cipher,
                                 MeterRegistry metrics, LeaderLeaseService leases) {
        this.endpoints = endpoints;
        this.cipher = cipher;
        this.metrics = metrics;
        this.leases = leases;
    }

    @Scheduled(fixedDelayString = "${webhook.security.reencrypt-delay-ms:60000}")
    @Transactional
    public void reencryptBatch() {
        if (!leases.acquire("secret-rotation", 90)) return;
        endpoints.findByKeyVersionNot(cipher.activeVersion(), PageRequest.of(0, 100)).forEach(endpoint -> {
            try {
                String plaintext = cipher.decrypt(endpoint.getEncryptedSecret());
                endpoint.setEncryptedSecret(cipher.encrypt(plaintext));
                endpoint.setKeyVersion(cipher.activeVersion());
                endpoint.setEncryptionAlgorithm("AES-256-GCM");
                endpoint.setSecretUpdatedAt(Instant.now());
                endpoints.save(endpoint);
                metrics.counter("webhook.security.secret.reencrypted").increment();
            } catch (RuntimeException failure) {
                metrics.counter("webhook.security.secret.reencrypt.failure",
                        "oldVersion", String.valueOf(endpoint.getKeyVersion())).increment();
            }
        });
    }
}

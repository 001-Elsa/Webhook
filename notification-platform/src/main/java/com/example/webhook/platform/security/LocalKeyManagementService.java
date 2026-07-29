package com.example.webhook.platform.security;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Default local KEK provider. Format: "1:base64Key,2:base64Key".
 * Production can switch to Vault Transit / AWS KMS via webhook.security.kms-provider.
 */
@Component
@ConditionalOnProperty(name = "webhook.security.kms-provider", havingValue = "local", matchIfMissing = true)
public class LocalKeyManagementService implements KeyManagementService {
    private final Map<Integer, SecretKey> keys;
    private final int activeVersion;
    private final MeterRegistry metrics;

    public LocalKeyManagementService(
            @Value("${webhook.security.encryption-keys:}") String configuredKeys,
            @Value("${webhook.security.encryption-key:}") String legacyKey,
            @Value("${webhook.security.active-key-version:1}") int activeVersion,
            MeterRegistry metrics) {
        this.keys = parse(configuredKeys, legacyKey);
        this.activeVersion = activeVersion;
        this.metrics = metrics;
        if (!keys.containsKey(activeVersion)) {
            throw new IllegalStateException("No webhook encryption key configured for active version " + activeVersion);
        }
    }

    @Override
    public int activeVersion() { return activeVersion; }

    @Override
    public SecretKey keyForVersion(int version) {
        SecretKey key = keys.get(version);
        if (key == null) throw new IllegalStateException("Webhook encryption key version is unavailable: " + version);
        metrics.counter("webhook.security.key.access", "version", String.valueOf(version)).increment();
        return key;
    }

    static Map<Integer, SecretKey> parse(String configuredKeys, String legacyKey) {
        Map<Integer, SecretKey> result = new HashMap<>();
        if (configuredKeys != null && !configuredKeys.isBlank()) {
            for (String entry : configuredKeys.split(",")) {
                int separator = entry.indexOf(':');
                if (separator <= 0) throw new IllegalStateException("Invalid encryption key ring entry");
                int version = Integer.parseInt(entry.substring(0, separator).trim());
                result.put(version, decode(entry.substring(separator + 1).trim()));
            }
        } else if (legacyKey != null && !legacyKey.isBlank()) {
            result.put(1, decode(legacyKey));
        }
        if (result.isEmpty()) throw new IllegalStateException("Webhook encryption key ring is empty");
        return Map.copyOf(result);
    }

    private static SecretKey decode(String encoded) {
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Webhook encryption key must be valid Base64", invalid);
        }
        if (decoded.length != 32) throw new IllegalStateException("Webhook encryption keys must be exactly 32 bytes");
        return new SecretKeySpec(decoded, "AES");
    }
}

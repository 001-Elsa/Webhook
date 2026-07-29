package com.example.webhook.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Map;

/**
 * Vault Transit adapter for DEK wrap/unwrap. Requires VAULT_ADDR + VAULT_TOKEN
 * and a transit key at {@code webhook.security.vault.transit-key}.
 */
@Component
@ConditionalOnProperty(name = "webhook.security.kms-provider", havingValue = "vault")
public class VaultTransitKeyManagementService implements KeyManagementService {
    private final RestClient client;
    private final String mount;
    private final String transitKey;
    private final int activeVersion;
    private final boolean configured;

    public VaultTransitKeyManagementService(
            @Value("${webhook.security.vault.addr:}") String addr,
            @Value("${webhook.security.vault.token:}") String token,
            @Value("${webhook.security.vault.mount:transit}") String mount,
            @Value("${webhook.security.vault.transit-key:eventrelay}") String transitKey,
            @Value("${webhook.security.active-key-version:1}") int activeVersion) {
        this.mount = mount;
        this.transitKey = transitKey;
        this.activeVersion = activeVersion;
        this.configured = addr != null && !addr.isBlank() && token != null && !token.isBlank();
        this.client = this.configured
                ? RestClient.builder()
                    .baseUrl(addr.endsWith("/") ? addr.substring(0, addr.length() - 1) : addr)
                    .defaultHeader("X-Vault-Token", token)
                    .build()
                : null;
    }

    @Override
    public int activeVersion() { return activeVersion; }

    @Override
    public SecretKey keyForVersion(int version) {
        throw new UnsupportedOperationException(
                "Vault Transit does not export KEKs; use wrapDataKey/unwrapDataKey");
    }

    @Override
    public byte[] wrapDataKey(byte[] dek) {
        requireConfigured();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = client.post()
                .uri("/v1/{mount}/encrypt/{key}", mount, transitKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("plaintext", Base64.getEncoder().encodeToString(dek)))
                .retrieve()
                .body(Map.class);
        String ciphertext = extractCiphertext(body);
        return ciphertext.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public byte[] unwrapDataKey(int kekVersion, byte[] wrappedDek) {
        requireConfigured();
        String ciphertext = new String(wrappedDek, java.nio.charset.StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = client.post()
                .uri("/v1/{mount}/decrypt/{key}", mount, transitKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("ciphertext", ciphertext))
                .retrieve()
                .body(Map.class);
        String plaintext = extractPlaintext(body);
        return Base64.getDecoder().decode(plaintext);
    }

    private void requireConfigured() {
        if (!configured) {
            throw new UnsupportedOperationException(
                    "Vault Transit KMS is selected but VAULT_ADDR/VAULT_TOKEN (or webhook.security.vault.*) are not configured");
        }
    }

    @SuppressWarnings("unchecked")
    private static String extractCiphertext(Map<String, Object> body) {
        if (body == null || !(body.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException("Vault encrypt response missing data.ciphertext");
        }
        Object ciphertext = ((Map<String, Object>) data).get("ciphertext");
        if (ciphertext == null) throw new IllegalStateException("Vault encrypt response missing data.ciphertext");
        return ciphertext.toString();
    }

    @SuppressWarnings("unchecked")
    private static String extractPlaintext(Map<String, Object> body) {
        if (body == null || !(body.get("data") instanceof Map<?, ?> data)) {
            throw new IllegalStateException("Vault decrypt response missing data.plaintext");
        }
        Object plaintext = ((Map<String, Object>) data).get("plaintext");
        if (plaintext == null) throw new IllegalStateException("Vault decrypt response missing data.plaintext");
        return plaintext.toString();
    }
}

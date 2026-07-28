package com.example.webhook.platform.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class WebhookSecretCipher {
    private static final int NONCE_BYTES = 12;
    private final KeyManagementService keyManagement;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public WebhookSecretCipher(KeyManagementService keyManagement) {
        this.keyManagement = keyManagement;
    }

    /** Compatibility constructor for existing unit tests and embedded use. */
    public WebhookSecretCipher(String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("WEBHOOK_ENCRYPTION_KEY must be valid Base64", ex);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException("WEBHOOK_ENCRYPTION_KEY must decode to exactly 32 bytes");
        }
        SecretKey key = new SecretKeySpec(decoded, "AES");
        this.keyManagement = new KeyManagementService() {
            public int activeVersion() { return 1; }
            public SecretKey keyForVersion(int version) {
                if (version != 1) throw new IllegalStateException("Unknown key version " + version);
                return key;
            }
        };
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) throw new IllegalArgumentException("Webhook secret is required");
        int version = keyManagement.activeVersion();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keyManagement.keyForVersion(version), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);
            return "v" + version + ":" + Base64.getEncoder().encodeToString(packed);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not encrypt webhook secret", ex);
        }
    }

    public String decrypt(String encoded) {
        int version = versionOf(encoded);
        try {
            int separator = encoded.indexOf(':');
            byte[] packed = Base64.getDecoder().decode(encoded.substring(separator + 1));
            if (packed.length <= NONCE_BYTES) throw new IllegalArgumentException("Encrypted secret is truncated");
            byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keyManagement.keyForVersion(version), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not decrypt webhook secret", ex);
        }
    }

    public int activeVersion() { return keyManagement.activeVersion(); }

    public int versionOf(String value) {
        if (!isEncrypted(value)) throw new IllegalStateException("Webhook secret is not encrypted");
        try {
            return Integer.parseInt(value.substring(1, value.indexOf(':')));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid webhook secret key version", invalid);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.matches("^v\\d+:.+");
    }
}

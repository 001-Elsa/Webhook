package com.example.webhook.platform.security;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Envelope encryption: random DEK encrypts plaintext (AES-GCM), KEK from
 * {@link KeyManagementService} wraps the DEK.
 * Format: {@code env:v{kekVersion}:{base64(wrappedDek)}:{base64(iv+ciphertext)}}
 */
@Component
public class EnvelopeEncryptionService {
    private static final int NONCE_BYTES = 12;
    private static final int DEK_BITS = 256;
    private final KeyManagementService keyManagement;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeEncryptionService(KeyManagementService keyManagement) {
        this.keyManagement = keyManagement;
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) throw new IllegalArgumentException("Webhook secret is required");
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(DEK_BITS, random);
            SecretKey dek = generator.generateKey();
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, dek, new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, packed, 0, nonce.length);
            System.arraycopy(encrypted, 0, packed, nonce.length, encrypted.length);

            int kekVersion = keyManagement.activeVersion();
            byte[] wrappedDek = keyManagement.wrapDataKey(dek.getEncoded());
            return "env:v" + kekVersion + ":"
                    + Base64.getEncoder().encodeToString(wrappedDek) + ":"
                    + Base64.getEncoder().encodeToString(packed);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not envelope-encrypt webhook secret", ex);
        }
    }

    public String decrypt(String encoded) {
        try {
            String[] parts = encoded.split(":", 4);
            if (parts.length != 4 || !"env".equals(parts[0]) || !parts[1].startsWith("v")) {
                throw new IllegalArgumentException("Not an envelope ciphertext");
            }
            int kekVersion = Integer.parseInt(parts[1].substring(1));
            byte[] wrappedDek = Base64.getDecoder().decode(parts[2]);
            byte[] packed = Base64.getDecoder().decode(parts[3]);
            if (packed.length <= NONCE_BYTES) throw new IllegalArgumentException("Encrypted secret is truncated");

            byte[] dekBytes = keyManagement.unwrapDataKey(kekVersion, wrappedDek);
            SecretKey dek = new SecretKeySpec(dekBytes, "AES");
            byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            byte[] encrypted = java.util.Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, dek, new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (RuntimeException ex) {
            throw ex instanceof IllegalStateException || ex instanceof IllegalArgumentException
                    ? (RuntimeException) ex
                    : new IllegalStateException("Could not envelope-decrypt webhook secret", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not envelope-decrypt webhook secret", ex);
        }
    }

    public boolean isEnvelope(String value) {
        return value != null && value.startsWith("env:v");
    }

    public int kekVersionOf(String value) {
        if (!isEnvelope(value)) throw new IllegalStateException("Not an envelope ciphertext");
        try {
            String versionToken = value.split(":", 3)[1];
            return Integer.parseInt(versionToken.substring(1));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Invalid envelope key version", invalid);
        }
    }
}

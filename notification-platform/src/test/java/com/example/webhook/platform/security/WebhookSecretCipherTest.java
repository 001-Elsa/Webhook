package com.example.webhook.platform.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WebhookSecretCipherTest {
    private final WebhookSecretCipher cipher =
            new WebhookSecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

    @Test
    void encryptsWithEnvelopeAndDetectsTampering() {
        String first = cipher.encrypt("webhook-secret");
        String second = cipher.encrypt("webhook-secret");

        assertThat(first).startsWith("env:v1:").isNotEqualTo(second).doesNotContain("webhook-secret");
        assertThat(cipher.decrypt(first)).isEqualTo("webhook-secret");
        assertThat(cipher.isEncrypted(first)).isTrue();

        String tampered = first.substring(0, first.length() - 2) + "AA";
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryptsLegacyDirectCiphertext() {
        WebhookSecretCipher legacyWriter = new WebhookSecretCipher(
                new KeyManagementService() {
                    private final javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(
                            java.util.Base64.getDecoder().decode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), "AES");
                    public int activeVersion() { return 1; }
                    public javax.crypto.SecretKey keyForVersion(int version) { return key; }
                },
                new EnvelopeEncryptionService(new KeyManagementService() {
                    private final javax.crypto.SecretKey key = new javax.crypto.spec.SecretKeySpec(
                            java.util.Base64.getDecoder().decode("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="), "AES");
                    public int activeVersion() { return 1; }
                    public javax.crypto.SecretKey keyForVersion(int version) { return key; }
                }),
                false);
        String legacy = legacyWriter.encrypt("legacy-secret");
        assertThat(legacy).startsWith("v1:");
        assertThat(cipher.decrypt(legacy)).isEqualTo("legacy-secret");
    }

    @Test
    void rejectsMissingOrWeakMasterKeys() {
        assertThatThrownBy(() -> new WebhookSecretCipher(""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}

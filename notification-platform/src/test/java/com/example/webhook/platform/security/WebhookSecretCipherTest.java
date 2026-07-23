package com.example.webhook.platform.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WebhookSecretCipherTest {
    private final WebhookSecretCipher cipher =
            new WebhookSecretCipher("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");

    @Test
    void encryptsWithRandomNonceAndDetectsTampering() {
        String first = cipher.encrypt("webhook-secret");
        String second = cipher.encrypt("webhook-secret");

        assertThat(first).startsWith("v1:").isNotEqualTo(second).doesNotContain("webhook-secret");
        assertThat(cipher.decrypt(first)).isEqualTo("webhook-secret");

        String tampered = first.substring(0, first.length() - 2) + "AA";
        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingOrWeakMasterKeys() {
        assertThatThrownBy(() -> new WebhookSecretCipher(""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}

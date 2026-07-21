package com.example.webhook.platform.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookUrlValidatorTest {
    private final WebhookUrlValidator validator = new WebhookUrlValidator();

    @Test
    void rejectsLocalhostUrls() {
        assertThatThrownBy(() -> validator.validate("http://localhost:8080/webhook"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    void rejectsPrivateNetworkAddresses() {
        assertThatThrownBy(() -> validator.validate("http://127.0.0.1/webhook"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");

        assertThatThrownBy(() -> validator.validate("http://10.0.0.8/webhook"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private or local");
    }

    @Test
    void acceptsPublicHttpUrls() {
        assertThatCode(() -> validator.validate("https://93.184.216.34/webhook"))
                .doesNotThrowAnyException();
    }
}

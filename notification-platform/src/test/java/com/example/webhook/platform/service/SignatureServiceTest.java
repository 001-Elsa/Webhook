package com.example.webhook.platform.service;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class SignatureServiceTest {
    private final SignatureService service = new SignatureService();

    @Test
    void signatureCanBeVerifiedAndRejectsTampering() {
        Instant timestamp = Instant.ofEpochMilli(1_700_000_000_000L);
        String signature = service.sign("secret", timestamp, "evt-1", "{\"ok\":true}");
        assertThat(service.verify("secret", String.valueOf(timestamp.toEpochMilli()), "evt-1", "{\"ok\":true}", signature)).isTrue();
        assertThat(service.verify("secret", String.valueOf(timestamp.toEpochMilli()), "evt-1", "{\"ok\":false}", signature)).isFalse();
    }
}

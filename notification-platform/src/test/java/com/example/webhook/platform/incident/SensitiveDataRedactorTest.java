package com.example.webhook.platform.incident;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataRedactorTest {
    @Test
    void removesCredentialsBeforeDiagnosis() {
        String result = new SensitiveDataRedactor().redact(
                "Authorization: Bearer abc.def api_key=secret-value https://x.test?p=1&token=raw");

        assertThat(result).doesNotContain("abc.def", "secret-value", "token=raw")
                .contains("[REDACTED]");
    }
}

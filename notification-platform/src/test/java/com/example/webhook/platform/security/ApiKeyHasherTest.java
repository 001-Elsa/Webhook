package com.example.webhook.platform.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyHasherTest {
    @Test
    void storesSaltedHashesAndUsesConstantTimeHashComparison() {
        ApiKeyHasher hasher = new ApiKeyHasher();
        String first = hasher.hash("correct-key");
        String second = hasher.hash("correct-key");

        assertThat(first).isNotEqualTo(second).doesNotContain("correct-key");
        assertThat(hasher.matches("correct-key", first)).isTrue();
        assertThat(hasher.matches("wrong-key", first)).isFalse();
        assertThat(hasher.matches("correct-key", "legacy-plaintext")).isFalse();
    }
}

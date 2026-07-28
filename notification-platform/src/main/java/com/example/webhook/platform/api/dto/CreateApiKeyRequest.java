package com.example.webhook.platform.api.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import java.time.Instant;
import java.util.Set;

public record CreateApiKeyRequest(
        @NotEmpty Set<String> scopes,
        @Future Instant expiresAt
) { }

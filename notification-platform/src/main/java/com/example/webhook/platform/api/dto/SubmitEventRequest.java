package com.example.webhook.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

public record SubmitEventRequest(
        String eventId,
        @NotBlank String type,
        Map<String, Object> data
) {
}

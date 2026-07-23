package com.example.webhook.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record SubmitEventRequest(
        @Size(max = 80) String eventId,
        @NotBlank @Size(max = 120) String type,
        Map<String, Object> data
) {
}

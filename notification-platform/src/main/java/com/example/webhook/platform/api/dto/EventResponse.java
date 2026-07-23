package com.example.webhook.platform.api.dto;

import com.example.webhook.platform.domain.EventRecord;
import com.example.webhook.platform.domain.EventStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;

public record EventResponse(Long id, String eventId, String type, String traceId, JsonNode data,
                            EventStatus status, Instant createdAt) {
    public static EventResponse from(EventRecord event, ObjectMapper objectMapper) {
        try {
            return new EventResponse(event.getId(), event.getEventId(), event.getType(), event.getTraceId(),
                    objectMapper.readTree(event.getPayload()), event.getStatus(), event.getCreatedAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Stored event payload is invalid JSON", ex);
        }
    }
}

package com.example.webhook.platform.api.dto;

public record EventSubmitResponse(String eventId, long deliveryTasks, boolean duplicate) {
}

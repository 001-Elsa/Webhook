package com.example.webhook.platform.api.dto;

public record DashboardStats(
        long endpoints,
        long events,
        long pending,
        long retrying,
        long succeeded,
        long dead
) {
}

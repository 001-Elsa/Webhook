package com.example.webhook.platform.domain;

public enum EventStatus {
    RECEIVED,
    DISPATCHING,
    COMPLETED,
    PARTIALLY_FAILED,
    DEAD
}

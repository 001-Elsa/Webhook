package com.example.webhook.platform.domain;

public enum OutboxMessageType {
    DELIVERY,
    RECOVERY,
    RETRY,
    DEAD
}

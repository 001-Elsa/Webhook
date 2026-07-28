package com.example.webhook.platform.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "eventrelay.roles.scheduler", havingValue = "true", matchIfMissing = true)
public class RetryScheduler {
    private final DeliveryService deliveries;
    private final LeaderLeaseService leases;

    public RetryScheduler(DeliveryService deliveries, LeaderLeaseService leases) {
        this.deliveries = deliveries;
        this.leases = leases;
    }

    @Scheduled(fixedDelayString = "${webhook.dispatcher.fixed-delay-ms:5000}")
    public void releaseDueDeliveries() {
        if (leases.acquire("retry-scheduler", 15)) deliveries.recoverDueTasks();
    }
}

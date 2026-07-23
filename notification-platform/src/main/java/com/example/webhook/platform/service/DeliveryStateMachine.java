package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.DeliveryStatus;
import com.example.webhook.platform.domain.DeliveryTask;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

@Component
public class DeliveryStateMachine {
    private static final Map<DeliveryStatus, Set<DeliveryStatus>> ALLOWED = Map.of(
            DeliveryStatus.PENDING, Set.of(DeliveryStatus.RETRYING, DeliveryStatus.SUCCEEDED, DeliveryStatus.DEAD),
            DeliveryStatus.RETRYING, Set.of(DeliveryStatus.RETRYING, DeliveryStatus.SUCCEEDED, DeliveryStatus.DEAD),
            DeliveryStatus.SUCCEEDED, Set.of(),
            DeliveryStatus.DEAD, Set.of(DeliveryStatus.RETRYING)
    );

    public void transition(DeliveryTask task, DeliveryStatus target) {
        DeliveryStatus current = task.getStatus();
        if (current == target && current == DeliveryStatus.RETRYING) return;
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw new IllegalStateException("Illegal delivery transition: " + current + " -> " + target);
        }
        task.setStatus(target);
    }
}

package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.EventRecord;
import com.example.webhook.platform.domain.EventStatus;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.Set;

@Component
public class EventStateMachine {
    private static final Map<EventStatus, Set<EventStatus>> ALLOWED = Map.of(
            EventStatus.RECEIVED, Set.of(EventStatus.DISPATCHING, EventStatus.COMPLETED),
            EventStatus.DISPATCHING, Set.of(EventStatus.COMPLETED, EventStatus.PARTIALLY_FAILED, EventStatus.DEAD),
            EventStatus.COMPLETED, Set.of(EventStatus.DISPATCHING),
            EventStatus.PARTIALLY_FAILED, Set.of(EventStatus.DISPATCHING, EventStatus.COMPLETED, EventStatus.DEAD),
            EventStatus.DEAD, Set.of(EventStatus.DISPATCHING, EventStatus.PARTIALLY_FAILED, EventStatus.COMPLETED)
    );

    public void transition(EventRecord event, EventStatus target) {
        EventStatus current = event.getStatus();
        if (current == target) return;
        if (!ALLOWED.getOrDefault(current, Set.of()).contains(target)) {
            throw new IllegalStateException("Illegal event transition: " + current + " -> " + target);
        }
        event.setStatus(target);
    }
}

package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.EventRecordRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class EventStatusReconciler {
    private final EventRecordRepository events;
    private final DeliveryTaskRepository deliveries;
    private final EventStateMachine stateMachine;
    private final MeterRegistry metrics;
    private final int batchSize;

    public EventStatusReconciler(EventRecordRepository events, DeliveryTaskRepository deliveries,
                                 EventStateMachine stateMachine, MeterRegistry metrics,
                                 @Value("${webhook.event-reconciler.batch-size:200}") int batchSize) {
        this.events = events;
        this.deliveries = deliveries;
        this.stateMachine = stateMachine;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${webhook.event-reconciler.fixed-delay-ms:5000}")
    @Transactional
    public void reconcile() {
        events.findByStatusInOrderByCreatedAtAsc(
                List.of(EventStatus.DISPATCHING),
                PageRequest.of(0, batchSize)).forEach(this::reconcileOne);
    }

    private void reconcileOne(EventRecord event) {
        long pending = deliveries.countByEventIdAndStatus(event.getId(), DeliveryStatus.PENDING)
                + deliveries.countByEventIdAndStatus(event.getId(), DeliveryStatus.RETRYING);
        long succeeded = deliveries.countByEventIdAndStatus(event.getId(), DeliveryStatus.SUCCEEDED);
        long dead = deliveries.countByEventIdAndStatus(event.getId(), DeliveryStatus.DEAD);
        EventStatus target = pending > 0 ? EventStatus.DISPATCHING
                : dead == 0 ? EventStatus.COMPLETED
                : succeeded == 0 ? EventStatus.DEAD : EventStatus.PARTIALLY_FAILED;
        if (event.getStatus() != target) {
            stateMachine.transition(event, target);
            events.save(event);
            metrics.counter("webhook.event.status.reconciled", "status", target.name()).increment();
        }
    }
}

package com.example.webhook.platform.service;

import com.example.webhook.platform.api.dto.EventSubmitResponse;
import com.example.webhook.platform.api.dto.SubmitEventRequest;
import com.example.webhook.platform.domain.DeliveryTask;
import com.example.webhook.platform.domain.EventRecord;
import com.example.webhook.platform.domain.EventStatus;
import com.example.webhook.platform.domain.WebhookEndpoint;
import com.example.webhook.platform.queue.DeliveryQueue;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.EventRecordRepository;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import com.example.webhook.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class EventService {
    private final ObjectMapper objectMapper;
    private final EventRecordRepository eventRepository;
    private final WebhookEndpointRepository endpointRepository;
    private final DeliveryTaskRepository deliveryRepository;
    private final EndpointMatcher matcher;
    private final DeliveryQueue deliveryQueue;

    public EventService(ObjectMapper objectMapper, EventRecordRepository eventRepository,
                        WebhookEndpointRepository endpointRepository, DeliveryTaskRepository deliveryRepository,
                        EndpointMatcher matcher, DeliveryQueue deliveryQueue) {
        this.objectMapper = objectMapper;
        this.eventRepository = eventRepository;
        this.endpointRepository = endpointRepository;
        this.deliveryRepository = deliveryRepository;
        this.matcher = matcher;
        this.deliveryQueue = deliveryQueue;
    }

    @Transactional
    public EventSubmitResponse submit(SubmitEventRequest request) {
        String eventId = request.eventId() == null || request.eventId().isBlank()
                ? UUID.randomUUID().toString()
                : request.eventId();
        var principal = RequestContext.principal();
        var duplicate = eventRepository.findByTenantIdAndEventId(principal.tenantId(), eventId);
        if (duplicate.isPresent()) {
            long count = deliveryRepository.countByEventEventId(eventId);
            return new EventSubmitResponse(eventId, count, true);
        }

        EventRecord event = new EventRecord();
        event.setTenantId(principal.tenantId());
        event.setAppId(principal.appId());
        event.setEventId(eventId);
        event.setType(request.type());
        event.setTraceId(RequestContext.traceId());
        event.setPayload(writePayload(request.data()));
        event.setStatus(EventStatus.DISPATCHING);
        eventRepository.save(event);

        List<WebhookEndpoint> endpoints = endpointRepository.findByTenantIdAndActiveTrue(principal.tenantId()).stream()
                .filter(endpoint -> matcher.supports(endpoint, request.type()))
                .toList();
        for (WebhookEndpoint endpoint : endpoints) {
            DeliveryTask task = new DeliveryTask();
            task.setEvent(event);
            task.setEndpoint(endpoint);
            task.setNextAttemptAt(Instant.now());
            deliveryRepository.save(task);
            enqueueAfterCommit(task.getId());
        }
        return new EventSubmitResponse(eventId, endpoints.size(), false);
    }

    private void enqueueAfterCommit(Long deliveryId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { deliveryQueue.enqueue(deliveryId); }
        });
    }
    private String writePayload(Object data) {
        try {
            return objectMapper.writeValueAsString(data == null ? java.util.Map.of() : data);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Event payload is not valid JSON", ex);
        }
    }
}

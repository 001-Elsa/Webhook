package com.example.webhook.platform.api;

import com.example.webhook.platform.api.dto.EventSubmitResponse;
import com.example.webhook.platform.api.dto.SubmitEventRequest;
import com.example.webhook.platform.api.dto.EventResponse;
import com.example.webhook.platform.domain.EventRecord;
import com.example.webhook.platform.repo.EventRecordRepository;
import com.example.webhook.platform.security.RequestContext;
import com.example.webhook.platform.service.EventService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventService eventService;
    private final EventRecordRepository eventRepository;
    private final ObjectMapper objectMapper;

    public EventController(EventService eventService, EventRecordRepository eventRepository, ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public EventSubmitResponse submit(@Valid @RequestBody SubmitEventRequest request) {
        return eventService.submit(request);
    }

    @GetMapping
    public List<EventResponse> list() {
        return eventRepository.findByTenantIdOrderByCreatedAtDesc(RequestContext.principal().tenantId()).stream()
                .map(event -> EventResponse.from(event, objectMapper)).toList();
    }
}

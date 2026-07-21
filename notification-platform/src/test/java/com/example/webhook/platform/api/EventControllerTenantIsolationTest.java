package com.example.webhook.platform.api;

import com.example.webhook.platform.domain.ClientRole;
import com.example.webhook.platform.repo.EventRecordRepository;
import com.example.webhook.platform.security.ApiPrincipal;
import com.example.webhook.platform.security.RequestContext;
import com.example.webhook.platform.service.EventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class EventControllerTenantIsolationTest {
    private final EventService eventService = mock(EventService.class);
    private final EventRecordRepository eventRepository = mock(EventRecordRepository.class);
    private final EventController controller = new EventController(eventService, eventRepository);

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    @Test
    void listReadsOnlyCurrentTenantEvents() {
        RequestContext.set(new ApiPrincipal("tenant-a", "app-a", ClientRole.ADMIN), "trace-a");
        when(eventRepository.findByTenantIdOrderByCreatedAtDesc("tenant-a")).thenReturn(List.of());

        controller.list();

        verify(eventRepository).findByTenantIdOrderByCreatedAtDesc("tenant-a");
        verify(eventRepository, never()).findAll();
    }
}

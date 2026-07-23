package com.example.webhook.platform.api;

import com.example.webhook.platform.domain.ClientRole;
import com.example.webhook.platform.domain.DeliveryStatus;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.EventRecordRepository;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import com.example.webhook.platform.security.ApiPrincipal;
import com.example.webhook.platform.security.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class DashboardControllerTenantIsolationTest {
    @AfterEach void clear() { RequestContext.clear(); }

    @Test
    void everyStatisticIsScopedToAuthenticatedTenant() {
        WebhookEndpointRepository endpoints = mock(WebhookEndpointRepository.class);
        EventRecordRepository events = mock(EventRecordRepository.class);
        DeliveryTaskRepository deliveries = mock(DeliveryTaskRepository.class);
        RequestContext.set(new ApiPrincipal("tenant-a", "admin-a", ClientRole.ADMIN), "trace-a");

        new DashboardController(endpoints, events, deliveries).stats();

        verify(endpoints).countByTenantId("tenant-a");
        verify(events).countByTenantId("tenant-a");
        for (DeliveryStatus status : DeliveryStatus.values()) {
            verify(deliveries).countByEventTenantIdAndStatus("tenant-a", status);
        }
    }
}

package com.example.webhook.platform.api;

import com.example.webhook.platform.api.dto.DashboardStats;
import com.example.webhook.platform.domain.DeliveryStatus;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.repo.EventRecordRepository;
import com.example.webhook.platform.repo.WebhookEndpointRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final WebhookEndpointRepository endpointRepository;
    private final EventRecordRepository eventRepository;
    private final DeliveryTaskRepository deliveryRepository;

    public DashboardController(WebhookEndpointRepository endpointRepository, EventRecordRepository eventRepository,
                               DeliveryTaskRepository deliveryRepository) {
        this.endpointRepository = endpointRepository;
        this.eventRepository = eventRepository;
        this.deliveryRepository = deliveryRepository;
    }

    @GetMapping("/stats")
    public DashboardStats stats() {
        return new DashboardStats(
                endpointRepository.count(),
                eventRepository.count(),
                deliveryRepository.countByStatus(DeliveryStatus.PENDING),
                deliveryRepository.countByStatus(DeliveryStatus.RETRYING),
                deliveryRepository.countByStatus(DeliveryStatus.SUCCEEDED),
                deliveryRepository.countByStatus(DeliveryStatus.DEAD)
        );
    }
}

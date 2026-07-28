package com.example.webhook.platform.api;

import com.example.webhook.platform.api.dto.DeliveryAttemptResponse;
import com.example.webhook.platform.api.dto.DeliveryResponse;
import com.example.webhook.platform.domain.DeliveryTask;
import com.example.webhook.platform.repo.DeliveryAttemptRepository;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.service.DeliveryService;
import com.example.webhook.platform.service.ReplayJobService;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.webhook.platform.security.RequestContext;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {
    private final DeliveryTaskRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final DeliveryService deliveryService;
    private final ReplayJobService replayJobs;

    public DeliveryController(DeliveryTaskRepository deliveryRepository, DeliveryAttemptRepository attemptRepository,
                              DeliveryService deliveryService) {
        this(deliveryRepository, attemptRepository, deliveryService, null);
    }

    @Autowired
    public DeliveryController(DeliveryTaskRepository deliveryRepository, DeliveryAttemptRepository attemptRepository,
                              DeliveryService deliveryService, ReplayJobService replayJobs) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.deliveryService = deliveryService;
        this.replayJobs = replayJobs;
    }

    @GetMapping
    public List<DeliveryResponse> list() {
        return deliveryRepository.findTop100ByEventTenantIdOrderByCreatedAtDesc(tenantId()).stream()
                .map(DeliveryResponse::from).toList();
    }

    @GetMapping("/attempts")
    public List<DeliveryAttemptResponse> attempts() {
        return attemptRepository.findTop200ByDeliveryEventTenantIdOrderByCreatedAtDesc(tenantId()).stream()
                .map(DeliveryAttemptResponse::from).toList();
    }

    @GetMapping("/{id}/attempts")
    public List<DeliveryAttemptResponse> attemptsByDelivery(@PathVariable Long id) {
        return attemptRepository.findByDeliveryIdAndDeliveryEventTenantIdOrderByCreatedAtDesc(id, tenantId()).stream()
                .map(DeliveryAttemptResponse::from).toList();
    }

    @GetMapping("/dead-letter")
    public List<DeliveryResponse> deadLetters() {
        return deliveryRepository.findTop100ByEventTenantIdAndStatusOrderByUpdatedAtDesc(
                        tenantId(), com.example.webhook.platform.domain.DeliveryStatus.DEAD).stream()
                .map(DeliveryResponse::from).toList();
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable Long id) {
        DeliveryTask task = deliveryService.retryNow(id, tenantId());
        return Map.of("deliveryId", task.getId(), "status", task.getStatus(), "nextAttemptAt", task.getNextAttemptAt());
    }

    @PostMapping("/dead-letter/replay")
    public Map<String, Object> replayDeadLetters() {
        if (replayJobs == null) throw new IllegalStateException("Replay job service is unavailable");
        var job = replayJobs.create(false, 100);
        return Map.of("jobId", job.getId(), "status", job.getStatus(),
                "message", "Asynchronous replay job created; approval may be required");
    }

    private String tenantId() {
        return RequestContext.principal().tenantId();
    }
}

package com.example.webhook.platform.api;

import com.example.webhook.platform.domain.DeliveryAttempt;
import com.example.webhook.platform.domain.DeliveryTask;
import com.example.webhook.platform.repo.DeliveryAttemptRepository;
import com.example.webhook.platform.repo.DeliveryTaskRepository;
import com.example.webhook.platform.service.DeliveryService;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/deliveries")
public class DeliveryController {
    private final DeliveryTaskRepository deliveryRepository;
    private final DeliveryAttemptRepository attemptRepository;
    private final DeliveryService deliveryService;

    public DeliveryController(DeliveryTaskRepository deliveryRepository, DeliveryAttemptRepository attemptRepository,
                              DeliveryService deliveryService) {
        this.deliveryRepository = deliveryRepository;
        this.attemptRepository = attemptRepository;
        this.deliveryService = deliveryService;
    }

    @GetMapping
    public List<DeliveryTask> list() {
        return deliveryRepository.findTop100ByOrderByCreatedAtDesc();
    }

    @GetMapping("/attempts")
    public List<DeliveryAttempt> attempts() {
        return attemptRepository.findTop200ByOrderByCreatedAtDesc();
    }

    @GetMapping("/{id}/attempts")
    public List<DeliveryAttempt> attemptsByDelivery(@PathVariable Long id) {
        return attemptRepository.findByDeliveryIdOrderByCreatedAtDesc(id);
    }

    @GetMapping("/dead-letter")
    public List<DeliveryTask> deadLetters() {
        return deliveryRepository.findTop100ByStatusOrderByUpdatedAtDesc(com.example.webhook.platform.domain.DeliveryStatus.DEAD);
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable Long id) {
        DeliveryTask task = deliveryService.retryNow(id);
        return Map.of("deliveryId", task.getId(), "status", task.getStatus(), "nextAttemptAt", task.getNextAttemptAt());
    }

    @PostMapping("/dead-letter/replay")
    public Map<String, Object> replayDeadLetters() {
        int count = deliveryService.retryDeadTasks();
        return Map.of("replayed", count);
    }
}

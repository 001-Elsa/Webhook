package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.OutboxMessage;
import com.example.webhook.platform.domain.OutboxMessageType;
import com.example.webhook.platform.repo.OutboxMessageRepository;
import org.springframework.stereotype.Service;

@Service
public class OutboxService {
    private final OutboxMessageRepository repository;

    public OutboxService(OutboxMessageRepository repository) { this.repository = repository; }

    public OutboxMessage add(Long deliveryId, OutboxMessageType type, int attemptNo) {
        OutboxMessage message = new OutboxMessage();
        message.setDeliveryId(deliveryId);
        message.setMessageType(type);
        message.setAttemptNo(attemptNo);
        return repository.save(message);
    }
}

package com.example.webhook.platform.service;

import com.example.webhook.platform.domain.OutboxMessage;
import com.example.webhook.platform.domain.OutboxMessageType;
import com.example.webhook.platform.repo.OutboxMessageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class OutboxService {
    private final OutboxMessageRepository repository;

    public OutboxService(OutboxMessageRepository repository) { this.repository = repository; }

    public OutboxMessage add(Long deliveryId, OutboxMessageType type, int attemptNo) {
        return add(deliveryId, type, attemptNo, Instant.now(), "IMMEDIATE");
    }

    public OutboxMessage addScheduled(Long deliveryId, OutboxMessageType type, int attemptNo, Instant scheduledAt) {
        return add(deliveryId, type, attemptNo, scheduledAt, "DATABASE_SCHEDULER");
    }

    private OutboxMessage add(Long deliveryId, OutboxMessageType type, int attemptNo,
                              Instant scheduledAt, String source) {
        OutboxMessage message = new OutboxMessage();
        message.setDeliveryId(deliveryId);
        message.setMessageType(type);
        message.setAttemptNo(attemptNo);
        message.setNextAttemptAt(scheduledAt);
        message.setScheduledSource(source);
        message.setLogicalPartition((short) Math.floorMod(deliveryId, 16));
        return repository.save(message);
    }

    /** Creates one durable recovery message per delivery attempt; duplicate scans are no-ops. */
    @Transactional
    public boolean addRecoveryIfAbsent(Long deliveryId, int attemptNo) {
        return repository.addRecoveryIfAbsent(deliveryId, OutboxMessageType.RECOVERY.name(), attemptNo) == 1;
    }
}

package com.example.webhook.platform;

import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.repo.*;
import com.example.webhook.platform.service.OutboxBatchStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies concurrent publisher replicas receive disjoint SKIP LOCKED batches.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "webhook.dispatcher.fixed-delay-ms=600000",
        "webhook.outbox.fixed-delay-ms=600000",
        "webhook.demo-receiver-url=http://localhost:9/webhook",
        "webhook.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "eventrelay.roles.publisher=false",
        "eventrelay.roles.scheduler=false",
        "eventrelay.roles.worker=false"
})
class OutboxBatchStoreIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("event_relay").withUsername("eventrelay").withPassword("eventrelay");
    @Container static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management-alpine");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired OutboxBatchStore batchStore;
    @Autowired WebhookEndpointRepository endpoints;
    @Autowired EventRecordRepository events;
    @Autowired DeliveryTaskRepository deliveries;
    @Autowired OutboxMessageRepository outbox;

    @Test
    void concurrentClaimBatchCallsReceiveDisjointMessageIds() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 20; i++) {
            seedPendingOutbox("skip-locked-tenant", now.minusSeconds(i + 1));
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Instant leaseA = now.plusSeconds(60);
            Instant leaseB = now.plusSeconds(90);
            Future<List<OutboxMessage>> first = pool.submit(() -> claimAfterSignal(ready, start, "worker-a", now, leaseA));
            Future<List<OutboxMessage>> second = pool.submit(() -> claimAfterSignal(ready, start, "worker-b", now, leaseB));
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<OutboxMessage> batchA = first.get(60, TimeUnit.SECONDS);
            List<OutboxMessage> batchB = second.get(60, TimeUnit.SECONDS);

            assertThat(batchA).isNotEmpty();
            assertThat(batchB).isNotEmpty();
            Set<Long> idsA = batchA.stream().map(OutboxMessage::getId).collect(Collectors.toSet());
            Set<Long> idsB = batchB.stream().map(OutboxMessage::getId).collect(Collectors.toSet());
            Set<Long> overlap = new HashSet<>(idsA);
            overlap.retainAll(idsB);
            assertThat(overlap).as("SKIP LOCKED must yield disjoint leased batches").isEmpty();
            assertThat(idsA).hasSize(batchA.size());
            assertThat(idsB).hasSize(batchB.size());
        } finally {
            pool.shutdownNow();
        }
    }

    private List<OutboxMessage> claimAfterSignal(CountDownLatch ready, CountDownLatch start,
                                                 String owner, Instant now, Instant lockedUntil)
            throws InterruptedException {
        ready.countDown();
        assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
        return batchStore.claimBatch(owner, 10, 100, now, lockedUntil);
    }

    private void seedPendingOutbox(String tenantId, Instant nextAttemptAt) {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setTenantId(tenantId);
        endpoint.setName("endpoint-" + System.nanoTime());
        endpoint.setUrl("https://93.184.216.34/webhook");
        endpoint.setEncryptedSecret("v1:test-value-not-read");
        endpoint.setEventTypes("*");
        endpoints.save(endpoint);

        EventRecord event = new EventRecord();
        event.setTenantId(tenantId);
        event.setAppId("skip-locked-app");
        event.setEventId("evt-" + System.nanoTime());
        event.setType("skip.locked");
        event.setPayload("{}");
        event.setStatus(EventStatus.DISPATCHING);
        events.save(event);

        DeliveryTask delivery = new DeliveryTask();
        delivery.setEvent(event);
        delivery.setEndpoint(endpoint);
        delivery.setNextAttemptAt(nextAttemptAt);
        deliveries.save(delivery);

        OutboxMessage message = new OutboxMessage();
        message.setDeliveryId(delivery.getId());
        message.setMessageType(OutboxMessageType.DELIVERY);
        message.setAttemptNo(0);
        message.setStatus(OutboxStatus.PENDING);
        message.setNextAttemptAt(nextAttemptAt);
        message.setLogicalPartition((short) (delivery.getId() % 16));
        outbox.save(message);
    }
}

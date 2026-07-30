package com.example.webhook.platform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;
import org.awaitility.Awaitility;
import org.testcontainers.DockerClientFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import com.example.webhook.platform.domain.*;
import com.example.webhook.platform.repo.*;
import com.example.webhook.platform.service.OutboxService;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"webhook.dispatcher.fixed-delay-ms=600000",
        "webhook.demo-receiver-url=http://localhost:9/webhook",
        "webhook.security.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // A failed connection attempt must not consume the whole Awaitility window after a restart.
        "spring.datasource.hikari.connection-timeout=2000"})
class InfrastructureIntegrationTest {
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

    @Autowired DataSource dataSource;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired StringRedisTemplate redis;
    @Autowired WebhookEndpointRepository endpoints;
    @Autowired EventRecordRepository events;
    @Autowired DeliveryTaskRepository deliveries;
    @Autowired OutboxMessageRepository outbox;
    @Autowired ReplayJobRepository replayJobs;
    @Autowired TransactionTemplate transactions;
    @Autowired OutboxService outboxService;

    @Test
    void allProductionInfrastructureIsReachable() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
        }
        assertThat(rabbitTemplate.getConnectionFactory().createConnection().isOpen()).isTrue();
        redis.opsForValue().set("integration:ready", "true");
        assertThat(redis.opsForValue().get("integration:ready")).isEqualTo("true");
    }

    @Test
    void businessRowsAndOutboxRollbackAtomically() {
        long eventsBefore = events.count();
        long deliveriesBefore = deliveries.count();
        long outboxBefore = outbox.count();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            WebhookEndpoint endpoint = new WebhookEndpoint();
            endpoint.setTenantId("rollback-tenant");
            endpoint.setName("rollback-endpoint");
            endpoint.setUrl("https://93.184.216.34/webhook");
            endpoint.setEncryptedSecret("v1:test-value-not-read");
            endpoint.setEventTypes("*");
            endpoints.save(endpoint);

            EventRecord event = new EventRecord();
            event.setTenantId("rollback-tenant");
            event.setAppId("rollback-app");
            event.setEventId("rollback-" + System.nanoTime());
            event.setType("rollback.test");
            event.setPayload("{}");
            event.setStatus(EventStatus.DISPATCHING);
            events.save(event);

            DeliveryTask delivery = new DeliveryTask();
            delivery.setEvent(event);
            delivery.setEndpoint(endpoint);
            delivery.setNextAttemptAt(Instant.now());
            deliveries.save(delivery);

            OutboxMessage message = new OutboxMessage();
            message.setDeliveryId(delivery.getId());
            message.setMessageType(OutboxMessageType.DELIVERY);
            message.setAttemptNo(0);
            outbox.save(message);
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(events.count()).isEqualTo(eventsBefore);
        assertThat(deliveries.count()).isEqualTo(deliveriesBefore);
        assertThat(outbox.count()).isEqualTo(outboxBefore);
    }

    @Test
    void recoveryOutboxIsIdempotentAndOnlyOneWorkerCanClaimTheDueDelivery() {
        Long deliveryId = transactions.execute(status -> {
            WebhookEndpoint endpoint = new WebhookEndpoint();
            endpoint.setTenantId("recovery-tenant");
            endpoint.setName("recovery-endpoint-" + System.nanoTime());
            endpoint.setUrl("https://93.184.216.34/webhook");
            endpoint.setEncryptedSecret("v1:test-value-not-read");
            endpoint.setEventTypes("*");
            endpoints.save(endpoint);

            EventRecord event = new EventRecord();
            event.setTenantId("recovery-tenant");
            event.setAppId("recovery-app");
            event.setEventId("recovery-" + System.nanoTime());
            event.setType("recovery.test");
            event.setPayload("{}");
            event.setStatus(EventStatus.DISPATCHING);
            events.save(event);

            DeliveryTask delivery = new DeliveryTask();
            delivery.setEvent(event);
            delivery.setEndpoint(endpoint);
            delivery.setNextAttemptAt(Instant.now().minusSeconds(1));
            return deliveries.save(delivery).getId();
        });

        assertThat(outboxService.addRecoveryIfAbsent(deliveryId, 0)).isTrue();
        assertThat(outboxService.addRecoveryIfAbsent(deliveryId, 0)).isFalse();

        Instant now = Instant.now();
        int firstClaim = transactions.execute(status -> deliveries.claimDueTask(deliveryId,
                java.util.List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), now, "worker-a", now.plusSeconds(60)));
        int secondClaim = transactions.execute(status -> deliveries.claimDueTask(deliveryId,
                java.util.List.of(DeliveryStatus.PENDING, DeliveryStatus.RETRYING), now, "worker-b", now.plusSeconds(60)));

        assertThat(firstClaim).isEqualTo(1);
        assertThat(secondClaim).isZero();
    }

    @Test
    void twentyConcurrentWorkersReceiveExactlyOneHalfOpenProbePermit() throws Exception {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setTenantId("half-open-tenant");
        endpoint.setName("half-open-" + System.nanoTime());
        endpoint.setUrl("https://93.184.216.34/webhook");
        endpoint.setEncryptedSecret("v1:test-value-not-read");
        endpoint.setEventTypes("*");
        endpoint.setCircuitState(CircuitState.HALF_OPEN);
        endpoint.setHalfOpenMaxProbes(1);
        WebhookEndpoint savedEndpoint = endpoints.saveAndFlush(endpoint);

        ExecutorService workers = Executors.newFixedThreadPool(20);
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> attempts = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                Long endpointId = savedEndpoint.getId();
                attempts.add(workers.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(30, TimeUnit.SECONDS)).isTrue();
                    return endpoints.acquireHalfOpenProbe(endpointId, CircuitState.HALF_OPEN, 1);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int granted = 0;
            for (Future<Integer> attempt : attempts) granted += attempt.get(60, TimeUnit.SECONDS);

            assertThat(granted).isEqualTo(1);
            assertThat(endpoints.findById(savedEndpoint.getId()).orElseThrow().getHalfOpenProbes()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void expiredReplayJobIsRecoveredAndCanBeClaimedByAReplacementScheduler() {
        ReplayJob job = new ReplayJob();
        job.setTenantId("replay-recovery-tenant");
        job.setRequestedBy("requester-a");
        job.setStatus(ReplayJobStatus.RUNNING);
        job.setDryRun(true);
        job.setMaxDeliveries(10);
        job.setLockedBy("scheduler-crashed");
        job.setLockedUntil(Instant.now().minusSeconds(1));
        job.setHeartbeatAt(Instant.now().minusSeconds(31));
        job = replayJobs.saveAndFlush(job);

        Instant now = Instant.now();
        assertThat(replayJobs.recoverExpiredRunning(now, ReplayJobStatus.PENDING, ReplayJobStatus.RUNNING)).isEqualTo(1);
        assertThat(replayJobs.claimPending(job.getId(), now, now.plusSeconds(30), "scheduler-replacement",
                ReplayJobStatus.RUNNING, ReplayJobStatus.PENDING)).isEqualTo(1);

        ReplayJob recovered = replayJobs.findById(job.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(ReplayJobStatus.RUNNING);
        assertThat(recovered.getLockedBy()).isEqualTo("scheduler-replacement");
        assertThat(recovered.getHeartbeatAt()).isNotNull();
    }

    @Test
    @Tag("fault-injection")
    void clientsRecoverAfterInfrastructureContainerRestarts() {
        restart(MYSQL);
        Awaitility.await().ignoreExceptions().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
                assertThat(connection.isValid(2)).isTrue();
            }
        });
        evictDatabaseConnections();
        Awaitility.await().ignoreExceptions().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            try (var connection = dataSource.getConnection()) {
                assertThat(connection.isValid(2)).isTrue();
            }
        });

        restart(REDIS);
        Awaitility.await().ignoreExceptions().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            redis.opsForValue().set("integration:redis-restarted", "true");
            assertThat(redis.opsForValue().get("integration:redis-restarted")).isEqualTo("true");
        });

        restart(RABBIT);
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory caching) {
            caching.resetConnection();
        }
        Awaitility.await().ignoreExceptions().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(rabbitTemplate.getConnectionFactory().createConnection().isOpen()).isTrue());
    }

    private void evictDatabaseConnections() {
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.getHikariPoolMXBean().softEvictConnections();
        }
    }

    private void restart(org.testcontainers.containers.ContainerState container) {
        DockerClientFactory.instance().client().restartContainerCmd(container.getContainerId())
                .withTimeout(30).exec();
    }
}

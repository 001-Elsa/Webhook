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
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;

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
    @Autowired TransactionTemplate transactions;

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
    @Tag("fault-injection")
    void clientsRecoverAfterInfrastructureContainerRestarts() {
        restart(MYSQL);
        evictDatabaseConnections();
        Awaitility.await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            try (var connection = dataSource.getConnection()) {
                assertThat(connection.isValid(2)).isTrue();
            }
        });

        restart(REDIS);
        Awaitility.await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            redis.opsForValue().set("integration:redis-restarted", "true");
            assertThat(redis.opsForValue().get("integration:redis-restarted")).isEqualTo("true");
        });

        restart(RABBIT);
        if (rabbitTemplate.getConnectionFactory() instanceof CachingConnectionFactory caching) {
            caching.resetConnection();
        }
        Awaitility.await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
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

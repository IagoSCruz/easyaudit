package br.com.easyaudit.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.RabbitContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = MessagingTestApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "easyaudit.messaging.retry.initial-interval=50ms",
        "easyaudit.messaging.retry.multiplier=2",
        "easyaudit.messaging.retry.max-interval=500ms"})
class MessagingIT {

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
        RabbitContainerSupport.register(registry);
    }

    @Autowired
    RabbitTemplate rabbit;

    @BeforeEach
    void reset() {
        MessagingTestApplication.FAILING_ATTEMPTS.set(0);
        MessagingTestApplication.FAILING_TENANTS.clear();
        MessagingTestApplication.IDEMPOTENT_EFFECTS.set(0);
        MessagingTestApplication.SEEN_TENANTS.clear();
    }

    private Message json(UUID messageId, String tenant) {
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setMessageId(messageId.toString());
        if (tenant != null) {
            props.setHeader(MessagingHeaders.TENANT_ID, tenant);
        }
        return MessageBuilder.withBody("{\"x\":1}".getBytes()).andProperties(props).build();
    }

    @Test
    void permanentFailureGoesToDlqAfterThreeAttempts() {
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, "it.failing", json(UUID.randomUUID(), null));

        Message dead = rabbit.receive("it.failing.dlq", Duration.ofSeconds(15).toMillis());
        assertThat(dead).as("mensagem na DLQ").isNotNull();
        assertThat(MessagingTestApplication.FAILING_ATTEMPTS.get()).isEqualTo(3);
        // nada sobra na fila principal
        assertThat(rabbit.receive("it.failing", 500)).isNull();
    }

    @Test
    void tenantContextCoversEveryRetryAttempt() {
        String tenant = "11111111-1111-4111-8111-111111111111";
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, "it.failing", json(UUID.randomUUID(), tenant));

        assertThat(rabbit.receive("it.failing.dlq", Duration.ofSeconds(15).toMillis())).isNotNull();
        assertThat(MessagingTestApplication.FAILING_TENANTS).containsExactly(tenant, tenant, tenant);
    }

    @Test
    void sameEventIdDeliveredTwiceIsProcessedOnce() {
        UUID eventId = UUID.randomUUID();
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, "it.idempotent", json(eventId, null));
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, "it.idempotent", json(eventId, null));
        UUID other = UUID.randomUUID();
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, "it.idempotent", json(other, null));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(
                () -> assertThat(MessagingTestApplication.IDEMPOTENT_EFFECTS.get()).isEqualTo(2));
        // dá tempo para uma eventual terceira execução indevida
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(3)).untilAsserted(
                () -> assertThat(MessagingTestApplication.IDEMPOTENT_EFFECTS.get()).isEqualTo(2));
    }

    @Test
    void tenantHeaderBecomesTheTenantContext() {
        String tenant = "11111111-1111-4111-8111-111111111111";
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, "it.tenant", json(UUID.randomUUID(), tenant));
        rabbit.send(MessagingTopology.EVENTS_EXCHANGE, "it.tenant", json(UUID.randomUUID(), null));

        await().atMost(Duration.ofSeconds(15)).untilAsserted(
                () -> assertThat(MessagingTestApplication.SEEN_TENANTS).containsExactlyInAnyOrder(tenant, "none"));
    }
}

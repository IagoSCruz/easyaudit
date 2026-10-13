package br.com.easyaudit.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.contracts.events.EventType;
import br.com.easyaudit.platform.contract.EventContractValidator;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.RabbitContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = OutboxTestApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "easyaudit.messaging.outbox.republish-interval=PT1H"})
class OutboxIT {

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
        RabbitContainerSupport.register(registry);
    }

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    IncompleteEventPublications incomplete;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void clean() {
        OutboxTestApplication.BROKER_DOWN.set(false);
        while (rabbit.receive("it.outbox", 200) != null) {
            // esvazia a fila
        }
        jdbc.update("delete from messaging.event_publication");
    }

    private static EventEnvelope envelope() {
        EventEnvelope e = new EventEnvelope();
        e.setEventId(UUID.randomUUID());
        e.setEventType(EventType.AUDIT_COMPLETED);
        e.setSchemaVersion(1);
        e.setOccurredAt(OffsetDateTime.now());
        e.setTenantId(UUID.fromString(TENANT));
        e.setData(Map.of(
                "caseId", UUID.randomUUID().toString(),
                "revisionId", UUID.randomUUID().toString(),
                "classification", "DIVERGENT",
                "chargedAmount", 152.37,
                "failedChecks", java.util.List.of("TOTAL_VS_TABLE")));
        return e;
    }

    @Test
    void eventIsPublishedOnlyAfterCommit() {
        EventEnvelope event = envelope();
        Message[] insideTx = new Message[1];

        tx.executeWithoutResult(status -> {
            events.publishEvent(event);
            insideTx[0] = rabbit.receive("it.outbox", 700);
        });

        assertThat(insideTx[0]).as("nada publicado antes do commit").isNull();
        Message received = rabbit.receive("it.outbox", 10_000);
        assertThat(received).as("publicado depois do commit").isNotNull();
        assertThat(received.getMessageProperties().getReceivedRoutingKey()).isEqualTo("audit.completed");
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(event.getEventId().toString());
        assertThat((Object) received.getMessageProperties().getHeader(MessagingHeaders.TENANT_ID)).isEqualTo(TENANT);

        String body = new String(received.getBody());
        assertThat(new EventContractValidator().validateEnvelope(body)).isEmpty();
        assertThat(new EventContractValidator().validatePayload("AuditCompletedData", dataOf(body))).isEmpty();
    }

    @Test
    void rollbackPublishesNothing() {
        tx.executeWithoutResult(status -> {
            events.publishEvent(envelope());
            status.setRollbackOnly();
        });

        assertThat(rabbit.receive("it.outbox", 1500)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from messaging.event_publication", Integer.class)).isZero();
    }

    @Test
    void incompletePublicationsAreResent() {
        OutboxTestApplication.BROKER_DOWN.set(true);
        EventEnvelope event = envelope();
        tx.executeWithoutResult(status -> events.publishEvent(event));

        assertThat(rabbit.receive("it.outbox", 1500)).isNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from messaging.event_publication where completion_date is null", Integer.class))
                .isEqualTo(1);

        OutboxTestApplication.BROKER_DOWN.set(false);
        incomplete.resubmitIncompletePublications(publication -> true);

        Message received = rabbit.receive("it.outbox", 10_000);
        assertThat(received).as("reenviado").isNotNull();
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(event.getEventId().toString());
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "select count(*) from messaging.event_publication where completion_date is null",
                        Integer.class)).isZero());
    }

    private static String dataOf(String envelopeJson) {
        try {
            var node = new com.fasterxml.jackson.databind.json.JsonMapper().readTree(envelopeJson);
            return node.get("data").toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

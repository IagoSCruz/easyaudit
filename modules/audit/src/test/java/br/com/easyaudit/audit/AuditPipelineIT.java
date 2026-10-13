package br.com.easyaudit.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.contracts.events.EventType;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.messaging.MessagingHeaders;
import br.com.easyaudit.platform.messaging.MessagingTopology;
import br.com.easyaudit.platform.messaging.OutboxConfiguration;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.RabbitContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Pipeline completo pela fila: ingestão → normalização → {@code document.normalized} → caso de auditoria. */
@SpringBootTest(classes = {AuditTestApplication.class, OutboxConfiguration.class}, properties = {
        "spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=true",
        "easyaudit.messaging.retry.initial-interval=50ms", "easyaudit.messaging.retry.multiplier=2",
        "easyaudit.messaging.retry.max-interval=500ms"})
class AuditPipelineIT extends AuditFlowSupport {

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        RabbitContainerSupport.register(registry);
    }

    @Autowired
    RabbitTemplate rabbit;

    @Test
    void anUploadedCteReachesAClassifiedCase() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(31).volume(1, "10.000", "12.500").build();
        var nfeDoc = as(() -> ingest.ingest(br.com.easyaudit.documents.domain.Source.UPLOAD, "n.xml", nfe.bytes()).documentId());
        var cteDoc = as(() -> ingest.ingest(br.com.easyaudit.documents.domain.Source.UPLOAD, "c.xml",
                cte().nfeKey(nfe.accessKey()).build().bytes()).documentId());

        await().atMost(Duration.ofSeconds(40)).until(() -> admin.queryForObject(
                "select count(*) from audit.audit_case where cte_document_id = ?", Integer.class, cteDoc) == 1);
        await().atMost(Duration.ofSeconds(20)).until(() -> "COMPLIANT".equals(admin.queryForObject(
                "select status from audit.audit_case where cte_document_id = ?", String.class, cteDoc)));
        assertThat(admin.queryForObject("select reference_amount from audit.audit_case where cte_document_id = ?",
                java.math.BigDecimal.class, cteDoc)).isEqualByComparingTo("66.02");
        assertThat(nfeDoc).isNotNull();
    }

    @Test
    void theSameNormalizationNotificationDeliveredTwiceCreatesOneCase() throws Exception {
        UUID cteDoc = process(cte().number(32).build());
        EventEnvelope event = new EventEnvelope();
        event.setEventId(UUID.randomUUID());
        event.setEventType(EventType.DOCUMENT_NORMALIZED);
        event.setSchemaVersion(1);
        event.setOccurredAt(OffsetDateTime.now());
        event.setTenantId(TwoTenants.A.value());
        event.setData(Map.of("documentId", cteDoc.toString(), "kind", "CTE", "accessKey", "x"));

        for (int i = 0; i < 2; i++) {
            rabbit.convertAndSend(MessagingTopology.EVENTS_EXCHANGE, "document.normalized", event, message -> {
                message.getMessageProperties().setMessageId(event.getEventId().toString());
                message.getMessageProperties().setHeader(MessagingHeaders.TENANT_ID, TwoTenants.A.toString());
                return message;
            });
        }
        // outro evento (eventId diferente) para o mesmo documento: o caso continua único
        EventEnvelope again = new EventEnvelope();
        again.setEventId(UUID.randomUUID());
        again.setEventType(EventType.DOCUMENT_NORMALIZED);
        again.setSchemaVersion(1);
        again.setOccurredAt(OffsetDateTime.now());
        again.setTenantId(TwoTenants.A.value());
        again.setData(event.getData());
        rabbit.convertAndSend(MessagingTopology.EVENTS_EXCHANGE, "document.normalized", again, message -> {
            message.getMessageProperties().setMessageId(again.getEventId().toString());
            message.getMessageProperties().setHeader(MessagingHeaders.TENANT_ID, TwoTenants.A.toString());
            return message;
        });

        await().atMost(Duration.ofSeconds(30)).until(() -> admin.queryForObject(
                "select count(*) from audit.audit_case where cte_document_id = ?", Integer.class, cteDoc) == 1);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(6)).until(() -> admin.queryForObject(
                "select count(*) from audit.audit_case where cte_document_id = ?", Integer.class, cteDoc) == 1);
        assertThat(admin.queryForObject("select count(*) from audit.audit_revision", Integer.class)).isEqualTo(1);
    }

    private UUID upload(FiscalXml xml) throws Exception {
        return as(() -> ingest.ingest(br.com.easyaudit.documents.domain.Source.UPLOAD, "x.xml", xml.bytes()).documentId());
    }

    private String caseStatus(UUID cteDoc) {
        var rows = admin.queryForList("select status from audit.audit_case where cte_document_id = ?", String.class, cteDoc);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Test
    void aFiscalCancellationCancelsTheCaseThroughTheQueues() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(41).volume(1, "10.000", "12.500").build();
        upload(nfe);
        FiscalXml cte = cte().number(41).nfeKey(nfe.accessKey()).build();
        UUID cteDoc = upload(cte);
        await().atMost(Duration.ofSeconds(40)).until(() -> "COMPLIANT".equals(caseStatus(cteDoc)));

        upload(br.com.easyaudit.documents.fixtures.CancellationFixture.forCte(cte.accessKey(), CARRIER_CNPJ).build());

        await().atMost(Duration.ofSeconds(40)).until(() -> "CANCELLED".equals(caseStatus(cteDoc)));
        assertThat(admin.queryForObject("select cancel_reason from audit.audit_case where cte_document_id = ?",
                String.class, cteDoc)).isEqualTo("CTE_CANCELLED");
    }

    @Test
    void aSubstituteCteCancelsTheOriginalCaseThroughTheQueues() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(42).volume(1, "10.000", "12.500").build();
        upload(nfe);
        FiscalXml original = cte().number(42).nfeKey(nfe.accessKey()).build();
        UUID originalDoc = upload(original);
        await().atMost(Duration.ofSeconds(40)).until(() -> "COMPLIANT".equals(caseStatus(originalDoc)));

        upload(cte().number(43).type(br.com.easyaudit.documents.fixtures.CteFixture.Type.SUBSTITUTE)
                .relatedKey(original.accessKey()).nfeKey(nfe.accessKey()).build());

        await().atMost(Duration.ofSeconds(40)).until(() -> "CANCELLED".equals(caseStatus(originalDoc)));
        assertThat(admin.queryForObject("select cancel_reason from audit.audit_case where cte_document_id = ?",
                String.class, originalDoc)).isEqualTo("CTE_SUBSTITUTED");
    }
}

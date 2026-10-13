package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.application.RawDocumentStorage;
import br.com.easyaudit.documents.domain.IngestionOutcome;
import br.com.easyaudit.documents.domain.IngestionOutcome.Status;
import br.com.easyaudit.documents.domain.RejectionReason;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.fixtures.CancellationFixture;
import br.com.easyaudit.documents.fixtures.Cnpjs;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.shared.TenantId;
import br.com.easyaudit.tenancy.TenancyFixtures;
import br.com.easyaudit.tenancy.application.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant A é a "loja" (raiz 45723174); o tenant B é o destinatário final (raiz 99888777). */
@SpringBootTest(classes = DocumentsTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@RecordApplicationEvents
class IngestDocumentIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    IngestDocument ingest;
    @Autowired
    RawDocumentStorage storage;
    @Autowired
    TenantService tenantService;
    @Autowired
    JdbcTemplate app;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ApplicationEvents applicationEvents;

    /** Superusuário: ignora a RLS, para conferir o que foi gravado em qualquer tenant. */
    JdbcTemplate owner;

    @BeforeEach
    void setUp() {
        owner = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        owner.execute("truncate documents.raw_document, documents.pending_cancellation, documents.document cascade");
        TenancyFixtures.seedTwoTenants(owner);
        tenantService.addCnpj(TwoTenants.A.value(), "45723174");
    }

    private IngestionOutcome as(TenantId tenant, FiscalXml xml) {
        try {
            return TenantContext.callAs(tenant, () -> ingest.ingest(Source.UPLOAD, "doc.xml", xml.bytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private IngestionOutcome asA(FiscalXml xml) {
        return as(TwoTenants.A, xml);
    }

    private List<EventEnvelope> receivedEvents() {
        return applicationEvents.stream(EventEnvelope.class).toList();
    }

    @Test
    void acceptsAnAuthorizedSignedCteOfTheTenantAndKeepsTheOriginalBytes() throws Exception {
        FiscalXml cte = CteFixture.builder().build();
        IngestionOutcome outcome = asA(cte);

        assertThat(outcome.status()).isEqualTo(Status.ACCEPTED);
        assertThat(outcome.documentId()).isNotNull();
        UUID id = outcome.documentId();
        byte[] stored = TenantContext.callAs(TwoTenants.A, () -> tx.execute(s -> storage.load(id).orElseThrow()));
        assertThat(stored).isEqualTo(cte.bytes());
        assertThat(owner.queryForObject("select raw_sha256 from documents.document where id = ?", String.class, id))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cte.bytes())));
        assertThat(owner.queryForObject("select status from documents.document where id = ?", String.class, id))
                .isEqualTo("RECEIVED");
    }

    @Test
    void publishesDocumentReceivedInTheSameTransaction() {
        IngestionOutcome outcome = asA(CteFixture.builder().build());
        var events = receivedEvents();
        assertThat(events).hasSize(1);
        EventEnvelope event = events.get(0);
        assertThat(event.getEventType().getValue()).isEqualTo("document.received");
        assertThat(event.getTenantId()).isEqualTo(TwoTenants.A.value());
        assertThat(event.getData()).containsEntry("documentId", outcome.documentId().toString())
                .containsEntry("kind", "CTE").containsEntry("cancellation", false);
    }

    @Test
    void acceptsNfeWhenIssuerOrRecipientBelongsToTheTenant() {
        assertThat(asA(NfeFixture.builder().build()).status()).isEqualTo(Status.ACCEPTED);
        // tenant B é o destinatário (raiz 99888777) da mesma NF-e
        assertThat(as(TwoTenants.B, NfeFixture.builder().build()).status()).isEqualTo(Status.ACCEPTED);
    }

    @Test
    void rejectsUnsupportedDocuments() {
        assertReason(CteFixture.builder().version("3.00").build(), RejectionReason.UNSUPPORTED_DOCUMENT);
        assertReason(CancellationFixture.forCte(CteFixture.builder().build().accessKey(), "11222333000181")
                .eventType("110110").build(), RejectionReason.UNSUPPORTED_DOCUMENT);
    }

    @Test
    void rejectsMalformedAndOversizedFiles() {
        IngestionOutcome malformed = ingestRaw("isto não é xml".getBytes());
        assertThat(malformed.reason()).isEqualTo(RejectionReason.MALFORMED_XML);
        IngestionOutcome large = ingestRaw(new byte[IngestDocument.MAX_XML_BYTES + 1]);
        assertThat(large.reason()).isEqualTo(RejectionReason.FILE_TOO_LARGE);
    }

    private IngestionOutcome ingestRaw(byte[] bytes) {
        try {
            return TenantContext.callAs(TwoTenants.A, () -> ingest.ingest(Source.UPLOAD, "x.xml", bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void rejectsDocumentsWithoutAuthorization() {
        assertReason(CteFixture.builder().withoutProtocol().build(), RejectionReason.NOT_AUTHORIZED);
        assertReason(CteFixture.builder().protocolStatus("110").number(2).build(), RejectionReason.NOT_AUTHORIZED);
        assertReason(NfeFixture.builder().protocolStatus("301").build(), RejectionReason.NOT_AUTHORIZED);
        assertReason(CancellationFixture.forCte(CteFixture.builder().build().accessKey(), "11222333000181")
                .registrationStatus("573").build(), RejectionReason.NOT_AUTHORIZED);
        assertThat(applicationEvents.stream(EventEnvelope.class).count()).isZero();
    }

    @Test
    void acceptsCStat150AndEventStatus136And155() {
        assertThat(asA(CteFixture.builder().protocolStatus("150").build()).status()).isEqualTo(Status.ACCEPTED);
        String key = CteFixture.builder().build().accessKey();
        assertThat(asA(CancellationFixture.forCte(key, "11222333000181").registrationStatus("136").sequence(1).build())
                .status()).isEqualTo(Status.ACCEPTED);
        assertThat(asA(CancellationFixture.forCte(key, "11222333000181").registrationStatus("155").sequence(2).build())
                .status()).isEqualTo(Status.ACCEPTED);
    }

    @Test
    void rejectsTamperedSignedContent() {
        FiscalXml tampered = CteFixture.builder().build().replacing("<vTPrest>152.37</vTPrest>", "<vTPrest>1.00</vTPrest>");
        assertReason(tampered, RejectionReason.INVALID_SIGNATURE);
        assertReason(CteFixture.builder().unsigned().number(3).build(), RejectionReason.INVALID_SIGNATURE);
    }

    @Test
    void rejectsHomologationDocumentsByDefault() {
        assertReason(CteFixture.builder().environment(2).build(), RejectionReason.HOMOLOGATION_DOCUMENT);
        assertReason(NfeFixture.builder().environment(2).build(), RejectionReason.HOMOLOGATION_DOCUMENT);
    }

    @Test
    void rejectsDocumentsOfOtherCompanies() {
        // tomador = outra empresa
        assertReason(CteFixture.builder().senderCnpj(Cnpjs.of("55555555", "0001")).build(),
                RejectionReason.NOT_TENANT_DOCUMENT);
        // NF-e entre duas empresas que não são o tenant
        assertReason(NfeFixture.builder().issuerCnpj(Cnpjs.of("55555555", "0001"))
                .recipientCnpj(Cnpjs.of("66666666", "0001")).build(), RejectionReason.NOT_TENANT_DOCUMENT);
    }

    @Test
    void takerRolesDecideOwnership() {
        // A só é tomador quando o CNPJ dele é o do toma3/toma4 escolhido
        String lojaA = Cnpjs.of("45723174", "0001");
        FiscalXml byDestinatario = CteFixture.builder().senderCnpj(Cnpjs.of("55555555", "0001")).recipientCnpj(lojaA)
                .taker(CteFixture.Taker.RECIPIENT).number(11).build();
        assertThat(asA(byDestinatario).status()).isEqualTo(Status.ACCEPTED);
        FiscalXml byToma4 = CteFixture.builder().senderCnpj(Cnpjs.of("55555555", "0001")).otherTaker(lojaA).number(12).build();
        assertThat(asA(byToma4).status()).isEqualTo(Status.ACCEPTED);
        FiscalXml notTaker = CteFixture.builder().senderCnpj(Cnpjs.of("55555555", "0001")).recipientCnpj(lojaA)
                .taker(CteFixture.Taker.SENDER).number(13).build();
        assertReason(notTaker, RejectionReason.NOT_TENANT_DOCUMENT);
    }

    @Test
    void duplicateReturnsTheExistingIdWithoutReprocessing() {
        FiscalXml cte = CteFixture.builder().build();
        IngestionOutcome first = asA(cte);
        long eventsAfterFirst = applicationEvents.stream(EventEnvelope.class).count();

        IngestionOutcome second = asA(cte);
        assertThat(second.status()).isEqualTo(Status.DUPLICATE);
        assertThat(second.documentId()).isEqualTo(first.documentId());
        assertThat(applicationEvents.stream(EventEnvelope.class).count()).isEqualTo(eventsAfterFirst);
        assertThat(owner.queryForObject("select count(*) from documents.document", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameKeyInDifferentTenantsKeepsSeparateRecords() {
        FiscalXml nfe = NfeFixture.builder().build();
        IngestionOutcome forA = asA(nfe);
        IngestionOutcome forB = as(TwoTenants.B, nfe);

        assertThat(forA.status()).isEqualTo(Status.ACCEPTED);
        assertThat(forB.status()).isEqualTo(Status.ACCEPTED);
        assertThat(forA.documentId()).isNotEqualTo(forB.documentId());
        assertThat(owner.queryForObject("select count(distinct tenant_id) from documents.document", Integer.class)).isEqualTo(2);
    }

    @Test
    void cancellationEventBeforeTheDocumentIsAcceptedAndStaysPending() {
        String cteKey = CteFixture.builder().build().accessKey();
        FiscalXml event = CancellationFixture.forCte(cteKey, "11222333000181").build();

        IngestionOutcome outcome = asA(event);
        assertThat(outcome.status()).isEqualTo(Status.ACCEPTED);
        assertThat(owner.queryForObject("select count(*) from documents.pending_cancellation where applied_at is null and access_key = ?",
                Integer.class, cteKey)).isEqualTo(1);
        assertThat(owner.queryForObject("select count(*) from documents.document", Integer.class)).isZero();
        assertThat(receivedEvents()).hasSize(1);
        assertThat(receivedEvents().get(0).getData()).containsEntry("cancellation", true).containsEntry("kind", "CTE");

        // o mesmo evento (chave, tipo e sequência) é duplicado
        IngestionOutcome again = asA(event);
        assertThat(again.status()).isEqualTo(Status.DUPLICATE);
        assertThat(again.documentId()).isEqualTo(outcome.documentId());
    }

    @Test
    void rejectsCancellationEventWithInvalidSignature() {
        FiscalXml event = CancellationFixture.forNfe(NfeFixture.builder().build().accessKey(), "45723174000110").build()
                .replacing("Cancelamento de teste da NF-e", "Outra justificativa");
        assertReason(event, RejectionReason.INVALID_SIGNATURE);
    }

    private void assertReason(FiscalXml xml, RejectionReason reason) {
        IngestionOutcome outcome = asA(xml);
        assertThat(outcome.status()).isEqualTo(Status.REJECTED);
        assertThat(outcome.reason()).isEqualTo(reason);
        assertThat(outcome.documentId()).isNull();
    }
}

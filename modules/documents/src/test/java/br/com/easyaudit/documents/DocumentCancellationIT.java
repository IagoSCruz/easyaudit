package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.documents.application.ApplyCancellations;
import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.application.NormalizeDocument;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.fixtures.CancellationFixture;
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

@SpringBootTest(classes = DocumentsTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@RecordApplicationEvents
class DocumentCancellationIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    IngestDocument ingest;
    @Autowired
    NormalizeDocument normalize;
    @Autowired
    ApplyCancellations cancellations;
    @Autowired
    TenantService tenants;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ApplicationEvents applicationEvents;

    JdbcTemplate admin;

    @BeforeEach
    void setUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate documents.document_link, documents.raw_document, documents.pending_cancellation, "
                + "documents.document cascade");
        TenancyFixtures.seedTwoTenants(admin);
        tenants.addCnpj(TwoTenants.A.value(), "45723174");
        tenants.addCnpj(TwoTenants.B.value(), "99888777");
    }

    private UUID ingestOne(TenantId tenant, FiscalXml xml) throws Exception {
        return TenantContext.callAs(tenant, () -> ingest.ingest(Source.UPLOAD, "x.xml", xml.bytes()).documentId());
    }

    private void normalize(TenantId tenant, UUID id) throws Exception {
        TenantContext.callAs(tenant, () -> tx.execute(s -> {
            normalize.normalize(id);
            return null;
        }));
    }

    /** O que o consumidor faz ao receber o evento de cancelamento. */
    private void deliverCancellation(TenantId tenant, UUID eventId) throws Exception {
        TenantContext.callAs(tenant, () -> tx.execute(s -> {
            cancellations.onEventReceived(eventId);
            return null;
        }));
    }

    private List<EventEnvelope> cancelledEvents() {
        return applicationEvents.stream(EventEnvelope.class)
                .filter(e -> "document.cancelled".equals(e.getEventType().getValue())).toList();
    }

    private String lifecycle(UUID id) {
        return admin.queryForObject("select lifecycle from documents.document where id = ?", String.class, id);
    }

    @Test
    void cancellingAnAlreadyProcessedCteMarksItAndNotifies() throws Exception {
        FiscalXml cte = CteFixture.builder().build();
        UUID cteId = ingestOne(TwoTenants.A, cte);
        normalize(TwoTenants.A, cteId);

        UUID eventId = ingestOne(TwoTenants.A, CancellationFixture.forCte(cte.accessKey(), "11222333000181").build());
        deliverCancellation(TwoTenants.A, eventId);

        assertThat(lifecycle(cteId)).isEqualTo("CANCELLED");
        assertThat(admin.queryForObject("select applied_at is not null from documents.pending_cancellation where id = ?", Boolean.class, eventId)).isTrue();
        assertThat(cancelledEvents()).hasSize(1);
        assertThat(cancelledEvents().get(0).getData()).containsEntry("documentId", cteId.toString())
                .containsEntry("kind", "CTE").containsEntry("accessKey", cte.accessKey());
    }

    @Test
    void cancellationArrivingBeforeTheDocumentStaysPendingAndAppliesRightAfterNormalization() throws Exception {
        FiscalXml cte = CteFixture.builder().number(2).build();
        UUID eventId = ingestOne(TwoTenants.A, CancellationFixture.forCte(cte.accessKey(), "11222333000181").build());
        deliverCancellation(TwoTenants.A, eventId);
        assertThat(admin.queryForObject("select applied_at is null from documents.pending_cancellation where id = ?", Boolean.class, eventId)).isTrue();
        assertThat(cancelledEvents()).isEmpty();

        UUID cteId = ingestOne(TwoTenants.A, cte);
        assertThat(lifecycle(cteId)).isEqualTo("ACTIVE"); // ainda não normalizado
        normalize(TwoTenants.A, cteId);

        assertThat(lifecycle(cteId)).isEqualTo("CANCELLED");
        assertThat(cancelledEvents()).hasSize(1);
        assertThat(admin.queryForObject("select applied_at is not null from documents.pending_cancellation where id = ?", Boolean.class, eventId)).isTrue();
    }

    @Test
    void nfeCancellationWorksTheSameWay() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(3).build();
        UUID nfeId = ingestOne(TwoTenants.A, nfe);
        normalize(TwoTenants.A, nfeId);
        UUID eventId = ingestOne(TwoTenants.A, CancellationFixture.forNfe(nfe.accessKey(), "45723174000110").build());
        deliverCancellation(TwoTenants.A, eventId);

        assertThat(lifecycle(nfeId)).isEqualTo("CANCELLED");
        assertThat(cancelledEvents().get(0).getData()).containsEntry("kind", "NFE");
    }

    @Test
    void redeliveryDoesNotNotifyAgain() throws Exception {
        FiscalXml cte = CteFixture.builder().number(4).build();
        UUID cteId = ingestOne(TwoTenants.A, cte);
        normalize(TwoTenants.A, cteId);
        UUID first = ingestOne(TwoTenants.A, CancellationFixture.forCte(cte.accessKey(), "11222333000181").sequence(1).build());
        UUID second = ingestOne(TwoTenants.A, CancellationFixture.forCte(cte.accessKey(), "11222333000181").sequence(2).build());
        deliverCancellation(TwoTenants.A, first);
        deliverCancellation(TwoTenants.A, first); // reentrega
        deliverCancellation(TwoTenants.A, second); // segundo evento para o mesmo documento

        assertThat(cancelledEvents()).hasSize(1);
        assertThat(lifecycle(cteId)).isEqualTo("CANCELLED");
    }

    @Test
    void cancellationOfOneTenantDoesNotTouchTheOther() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(5).build();
        UUID forA = ingestOne(TwoTenants.A, nfe);
        UUID forB = ingestOne(TwoTenants.B, nfe);
        normalize(TwoTenants.A, forA);
        normalize(TwoTenants.B, forB);

        UUID eventId = ingestOne(TwoTenants.A, CancellationFixture.forNfe(nfe.accessKey(), "45723174000110").build());
        deliverCancellation(TwoTenants.A, eventId);

        assertThat(lifecycle(forA)).isEqualTo("CANCELLED");
        assertThat(lifecycle(forB)).isEqualTo("ACTIVE");
    }
}

package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.application.NormalizeDocument;
import br.com.easyaudit.documents.domain.Source;
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

/** Vínculos CT-e ↔ NF-e e complementar/substituto nas duas ordens de chegada (sem RabbitMQ: chamadas diretas). */
@SpringBootTest(classes = DocumentsTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@RecordApplicationEvents
class DocumentLinkIT {

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
        tenants.addCnpj(TwoTenants.B.value(), "55555555");
    }

    /** Recebe o XML e o normaliza (o que o consumidor faz depois do evento). */
    private UUID receiveAndNormalize(TenantId tenant, FiscalXml xml) throws Exception {
        UUID id = TenantContext.callAs(tenant, () -> ingest.ingest(Source.UPLOAD, "x.xml", xml.bytes()).documentId());
        TenantContext.callAs(tenant, () -> tx.execute(s -> {
            normalize.normalize(id);
            return null;
        }));
        return id;
    }

    private List<EventEnvelope> linkResolvedEvents() {
        return applicationEvents.stream(EventEnvelope.class)
                .filter(e -> "document.link-resolved".equals(e.getEventType().getValue())).toList();
    }

    private String lifecycle(UUID id) {
        return admin.queryForObject("select lifecycle from documents.document where id = ?", String.class, id);
    }

    @Test
    void nfeArrivesBeforeTheCteSoTheLinkIsBornResolved() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(1).build();
        UUID nfeId = receiveAndNormalize(TwoTenants.A, nfe);
        UUID cteId = receiveAndNormalize(TwoTenants.A, CteFixture.builder().nfeKey(nfe.accessKey()).build());

        var link = admin.queryForMap("select resolved, target_document_id, link_type from documents.document_link where from_document_id = ?", cteId);
        assertThat(link.get("resolved")).isEqualTo(true);
        assertThat(link.get("target_document_id")).isEqualTo(nfeId);
        assertThat(link.get("link_type")).isEqualTo("CTE_NFE");
        assertThat(linkResolvedEvents()).isEmpty();
    }

    @Test
    void nfeArrivesAfterTheCteSoThePendingLinkIsResolvedAndNotified() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(2).build();
        UUID cteId = receiveAndNormalize(TwoTenants.A, CteFixture.builder().nfeKey(nfe.accessKey()).build());
        assertThat(admin.queryForObject("select resolved from documents.document_link where from_document_id = ?", Boolean.class, cteId)).isFalse();
        assertThat(linkResolvedEvents()).isEmpty();

        UUID nfeId = receiveAndNormalize(TwoTenants.A, nfe);

        var link = admin.queryForMap("select resolved, target_document_id from documents.document_link where from_document_id = ?", cteId);
        assertThat(link.get("resolved")).isEqualTo(true);
        assertThat(link.get("target_document_id")).isEqualTo(nfeId);
        assertThat(linkResolvedEvents()).hasSize(1);
        var data = linkResolvedEvents().get(0).getData();
        assertThat(data).containsEntry("documentId", cteId.toString()).containsEntry("linkType", "CTE_NFE")
                .containsEntry("targetKey", nfe.accessKey()).containsEntry("targetDocumentId", nfeId.toString());
    }

    @Test
    void cteWithTwoNfesKeepsOneResolvedAndOnePending() throws Exception {
        FiscalXml received = NfeFixture.builder().number(3).build();
        FiscalXml missing = NfeFixture.builder().number(4).build();
        receiveAndNormalize(TwoTenants.A, received);
        UUID cteId = receiveAndNormalize(TwoTenants.A,
                CteFixture.builder().nfeKey(received.accessKey()).nfeKey(missing.accessKey()).build());

        assertThat(admin.queryForObject("select count(*) from documents.document_link where from_document_id = ? and resolved", Integer.class, cteId)).isEqualTo(1);
        assertThat(admin.queryForObject("select count(*) from documents.document_link where from_document_id = ? and not resolved", Integer.class, cteId)).isEqualTo(1);
    }

    @Test
    void substituteMarksTheSubstitutedCteWhenItWasAlreadyThere() throws Exception {
        FiscalXml original = CteFixture.builder().number(10).build();
        UUID originalId = receiveAndNormalize(TwoTenants.A, original);
        assertThat(lifecycle(originalId)).isEqualTo("ACTIVE");

        UUID substituteId = receiveAndNormalize(TwoTenants.A, CteFixture.builder().type(CteFixture.Type.SUBSTITUTE)
                .relatedKey(original.accessKey()).number(11).build());

        assertThat(lifecycle(originalId)).isEqualTo("SUBSTITUTED");
        assertThat(lifecycle(substituteId)).isEqualTo("ACTIVE");
        var link = admin.queryForMap("select link_type, resolved, target_document_id from documents.document_link where from_document_id = ? and link_type = 'SUBSTITUTES'", substituteId);
        assertThat(link.get("link_type")).isEqualTo("SUBSTITUTES");
        assertThat(link.get("resolved")).isEqualTo(true);
        assertThat(link.get("target_document_id")).isEqualTo(originalId);
    }

    @Test
    void substituteArrivesFirstAndTheSubstitutedCteIsMarkedWhenItShowsUp() throws Exception {
        FiscalXml original = CteFixture.builder().number(12).build();
        UUID substituteId = receiveAndNormalize(TwoTenants.A, CteFixture.builder().type(CteFixture.Type.SUBSTITUTE)
                .relatedKey(original.accessKey()).number(13).build());

        UUID originalId = receiveAndNormalize(TwoTenants.A, original);

        assertThat(lifecycle(originalId)).isEqualTo("SUBSTITUTED");
        assertThat(linkResolvedEvents()).hasSize(1);
        assertThat(linkResolvedEvents().get(0).getData()).containsEntry("documentId", substituteId.toString())
                .containsEntry("linkType", "SUBSTITUTES").containsEntry("targetDocumentId", originalId.toString());
    }

    @Test
    void complementLinksToTheComplementedCte() throws Exception {
        FiscalXml original = CteFixture.builder().number(14).build();
        UUID originalId = receiveAndNormalize(TwoTenants.A, original);
        UUID complementId = receiveAndNormalize(TwoTenants.A, CteFixture.builder().type(CteFixture.Type.COMPLEMENT)
                .relatedKey(original.accessKey()).number(15).build());

        var link = admin.queryForMap("select link_type, resolved, target_document_id from documents.document_link where from_document_id = ?", complementId);
        assertThat(link.get("link_type")).isEqualTo("COMPLEMENTS");
        assertThat(link.get("target_document_id")).isEqualTo(originalId);
        assertThat(lifecycle(originalId)).isEqualTo("ACTIVE"); // complemento não substitui
    }

    @Test
    void anotherTenantsNfeDoesNotResolveTheLink() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(5).issuerCnpj("55555555000191").recipientCnpj("66666666000191").build();
        UUID cteId = receiveAndNormalize(TwoTenants.A, CteFixture.builder().nfeKey(nfe.accessKey()).build());
        // o tenant B recebe a NF-e de mesma chave
        receiveAndNormalize(TwoTenants.B, nfe);

        assertThat(admin.queryForObject("select resolved from documents.document_link where from_document_id = ?", Boolean.class, cteId)).isFalse();
        assertThat(linkResolvedEvents()).isEmpty();
    }
}

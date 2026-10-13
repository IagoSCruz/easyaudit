package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import br.com.easyaudit.documents.api.DocumentCatalog;
import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.application.NormalizeDocument;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.tenancy.TenancyFixtures;
import br.com.easyaudit.tenancy.application.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = DocumentsTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class DocumentDetailIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    IngestDocument ingest;
    @Autowired
    NormalizeDocument normalize;
    @Autowired
    DocumentCatalog catalog;
    @Autowired
    TenantService tenants;
    @Autowired
    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        var admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate documents.document_link, documents.raw_document, documents.pending_cancellation, "
                + "documents.document cascade");
        TenancyFixtures.seedTwoTenants(admin);
        tenants.addCnpj(TwoTenants.A.value(), "45723174");
    }

    private UUID processed(FiscalXml xml) throws Exception {
        UUID id = TenantContext.callAs(TwoTenants.A, () -> ingest.ingest(Source.UPLOAD, "x.xml", xml.bytes()).documentId());
        TenantContext.callAs(TwoTenants.A, () -> tx.execute(s -> {
            normalize.normalize(id);
            return null;
        }));
        return id;
    }

    @Test
    void cteWithTwoNfesOneReceivedAndOneNot() throws Exception {
        FiscalXml received = NfeFixture.builder().number(1).build();
        FiscalXml missing = NfeFixture.builder().number(2).build();
        UUID nfeId = processed(received);
        UUID cteId = processed(CteFixture.builder().nfeKey(received.accessKey()).nfeKey(missing.accessKey())
                .component("FRETE PESO", "100.00").component("GRIS", "52.37").build());

        mvc.perform(get("/api/v1/documents/" + cteId).header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSED"))
                .andExpect(jsonPath("$.cte.totalAmount").value(152.37))
                .andExpect(jsonPath("$.cte.components.length()").value(2))
                .andExpect(jsonPath("$.cte.components[0].code").value("FREIGHT_WEIGHT"))
                .andExpect(jsonPath("$.nfe").doesNotExist())
                .andExpect(jsonPath("$.links.length()").value(2))
                .andExpect(jsonPath("$.links[?(@.targetKey=='" + received.accessKey() + "')].resolved").value(true))
                .andExpect(jsonPath("$.links[?(@.targetKey=='" + received.accessKey() + "')].targetDocumentId").value(nfeId.toString()))
                .andExpect(jsonPath("$.links[?(@.targetKey=='" + missing.accessKey() + "')].resolved").value(false))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void nfeDetailHasTheCanonicalNfe() throws Exception {
        UUID id = processed(NfeFixture.builder().volume(1, "3.000", "3.500").build());
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nfe.totalGrossWeightKg").value(3.5))
                .andExpect(jsonPath("$.cte").doesNotExist())
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void substitutedCteShowsTheInverseLink() throws Exception {
        FiscalXml original = CteFixture.builder().number(10).build();
        UUID originalId = processed(original);
        FiscalXml substitute = CteFixture.builder().type(CteFixture.Type.SUBSTITUTE).relatedKey(original.accessKey()).number(11).build();
        UUID substituteId = processed(substitute);

        mvc.perform(get("/api/v1/documents/" + originalId).header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("SUBSTITUTED"))
                .andExpect(jsonPath("$.links[?(@.type=='SUBSTITUTED_BY')].targetKey").value(substitute.accessKey()))
                .andExpect(jsonPath("$.links[?(@.type=='SUBSTITUTED_BY')].targetDocumentId").value(substituteId.toString()))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        mvc.perform(get("/api/v1/documents/" + substituteId).header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(jsonPath("$.links[?(@.type=='SUBSTITUTES')].targetKey").value(original.accessKey()))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void failedDocumentHasNoCanonicalButKeepsTheReason() throws Exception {
        UUID id = processed(CteFixture.builder().withoutValuesGroup().build());
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value(org.hamcrest.Matchers.containsString("vPrest")))
                .andExpect(jsonPath("$.cte").doesNotExist())
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void theCatalogFacadeServesCanonicalDocumentsAndLinks() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(20).build();
        processed(nfe);
        UUID cteId = processed(CteFixture.builder().nfeKey(nfe.accessKey()).number(21).build());

        var cte = TenantContext.callAs(TwoTenants.A, () -> catalog.find(cteId)).orElseThrow();
        assertThat(cte.kind()).isEqualTo(DocumentCatalog.Kind.CTE);
        assertThat(cte.cte().getTotalAmount()).isEqualByComparingTo("152.37");
        assertThat(cte.nfe()).isNull();
        var links = TenantContext.callAs(TwoTenants.A, () -> catalog.linksFrom(cteId));
        assertThat(links).singleElement().satisfies(l -> {
            assertThat(l.type()).isEqualTo(DocumentCatalog.LinkType.CTE_NFE);
            assertThat(l.resolved()).isTrue();
        });
        var nfeDoc = TenantContext.callAs(TwoTenants.A, () -> catalog.findProcessed(DocumentCatalog.Kind.NFE, nfe.accessKey())).orElseThrow();
        assertThat(nfeDoc.nfe().getAccessKey()).isEqualTo(nfe.accessKey());
        // outro tenant não enxerga
        assertThat(TenantContext.callAs(TwoTenants.B, () -> catalog.find(cteId))).isEmpty();
    }
}

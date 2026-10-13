package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = DocumentsTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class DocumentQueryIT {

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
    TenantService tenants;

    JdbcTemplate admin;

    @BeforeEach
    void setUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate documents.raw_document, documents.pending_cancellation, documents.document cascade");
        TenancyFixtures.seedTwoTenants(admin);
        tenants.addCnpj(TwoTenants.A.value(), "45723174");
    }

    private UUID ingestFor(TenantId tenant, FiscalXml xml) throws Exception {
        return TenantContext.callAs(tenant, () -> ingest.ingest(Source.UPLOAD, "x.xml", xml.bytes()).documentId());
    }

    @Test
    void downloadReturnsTheExactBytesWithTheRecordedSha256() throws Exception {
        FiscalXml cte = CteFixture.builder().build();
        UUID id = ingestFor(TwoTenants.A, cte);

        byte[] downloaded = mvc.perform(get("/api/v1/documents/" + id + "/xml").header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/xml")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(cte.accessKey() + ".xml")))
                .andReturn().getResponse().getContentAsByteArray();

        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(downloaded));
        assertThat(downloaded).isEqualTo(cte.bytes());
        assertThat(sha).isEqualTo(admin.queryForObject("select raw_sha256 from documents.document where id = ?", String.class, id));
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rawSha256").value(sha));
    }

    @Test
    void detailShowsStatusAndConformsToTheContract() throws Exception {
        UUID id = ingestFor(TwoTenants.A, CteFixture.builder().build());
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.kind").value("CTE"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.lifecycle").value("ACTIVE"))
                .andExpect(jsonPath("$.processedAt").doesNotExist())
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void listingFiltersByKindStatusKeyAndPeriod() throws Exception {
        FiscalXml cte = CteFixture.builder().number(1).build();
        FiscalXml nfe = NfeFixture.builder().number(2).build();
        ingestFor(TwoTenants.A, cte);
        ingestFor(TwoTenants.A, nfe);
        String auth = TwoTenants.bearerA("VIEWER");

        mvc.perform(get("/api/v1/documents").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        mvc.perform(get("/api/v1/documents").param("kind", "CTE").header("Authorization", auth))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].accessKey").value(cte.accessKey()));
        mvc.perform(get("/api/v1/documents").param("accessKey", nfe.accessKey()).header("Authorization", auth))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].kind").value("NFE"));
        mvc.perform(get("/api/v1/documents").param("status", "PROCESSED").header("Authorization", auth))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/documents").param("receivedFrom", "2999-01-01T00:00:00Z").header("Authorization", auth))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/documents").param("receivedTo", "2999-01-01T00:00:00Z").header("Authorization", auth))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void listingIsPaginated() throws Exception {
        for (int i = 1; i <= 5; i++) {
            ingestFor(TwoTenants.A, CteFixture.builder().number(i).build());
        }
        mvc.perform(get("/api/v1/documents").param("size", "2").param("page", "2").header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(3));
    }

    @Test
    void anotherTenantSeesNothing() throws Exception {
        UUID id = ingestFor(TwoTenants.A, CteFixture.builder().build());
        String authB = TwoTenants.bearerB("TENANT_ADMIN");
        mvc.perform(get("/api/v1/documents/" + id).header("Authorization", authB)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/documents/" + id + "/xml").header("Authorization", authB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents").header("Authorization", authB))
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}

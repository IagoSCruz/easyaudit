package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.domain.IngestionOutcome.Status;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.tenancy.TenancyFixtures;
import br.com.easyaudit.tenancy.application.TenantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Com a flag de instalação ligada, documentos de homologação são aceitos. */
@SpringBootTest(classes = DocumentsTestApplication.class,
        properties = {"spring.jpa.hibernate.ddl-auto=none", "easyaudit.documents.accept-homologation=true"})
class HomologationFlagIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    IngestDocument ingest;
    @Autowired
    TenantService tenants;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void homologationDocumentIsAcceptedWhenTheInstallationAllowsIt() throws Exception {
        TenancyFixtures.seedTwoTenants(jdbc);
        tenants.addCnpj(TwoTenants.A.value(), "45723174");
        var outcome = TenantContext.callAs(TwoTenants.A, () -> ingest.ingest(Source.UPLOAD, "h.xml",
                CteFixture.builder().environment(2).build().bytes()));
        assertThat(outcome.status()).isEqualTo(Status.ACCEPTED);
    }
}

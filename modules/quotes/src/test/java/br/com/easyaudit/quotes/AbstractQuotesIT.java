package br.com.easyaudit.quotes;

import java.util.List;
import java.util.UUID;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.application.NormalizeDocument;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.shared.TenantId;
import br.com.easyaudit.tenancy.TenancyFixtures;
import br.com.easyaudit.tenancy.application.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/** Base dos testes de integração de cotações: banco limpo, dois tenants e ajudantes para receber NF-es. */
@SpringBootTest(classes = QuotesTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@RecordApplicationEvents
abstract class AbstractQuotesIT {

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

    /** Superusuário: ignora a RLS. */
    JdbcTemplate admin;

    @BeforeEach
    void baseSetUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate quotes.quote_revision, quotes.quote, documents.document_link, documents.raw_document, "
                + "documents.pending_cancellation, documents.document cascade");
        TenancyFixtures.seedTwoTenants(admin);
        tenants.addCnpj(TwoTenants.A.value(), "45723174");
    }

    /** Recebe e normaliza a NF-e (o que o pipeline faz) e devolve o id do documento. */
    UUID processNfe(TenantId tenant, FiscalXml xml) throws Exception {
        UUID id = TenantContext.callAs(tenant, () -> ingest.ingest(Source.UPLOAD, "n.xml", xml.bytes()).documentId());
        TenantContext.callAs(tenant, () -> tx.execute(s -> {
            normalize.normalize(id);
            return null;
        }));
        return id;
    }

    List<EventEnvelope> events(String type) {
        return applicationEvents.stream(EventEnvelope.class)
                .filter(e -> type.equals(e.getEventType().getValue())).toList();
    }
}

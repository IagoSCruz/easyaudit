package br.com.easyaudit.audit;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.tenancy.TenancyFixtures;
import br.com.easyaudit.tenancy.application.TenantService;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Base dos testes de integração do módulo audit: banco limpo e dois tenants (A é dono da raiz 45723174, a da loja). */
@SpringBootTest(classes = AuditTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@RecordApplicationEvents
abstract class AbstractAuditIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
    }

    /** O banco do contexto em uso (contextos em cache têm bancos diferentes, então não pode ser estático). */
    @Autowired
    org.springframework.core.env.Environment environment;

    @Autowired
    TenantService tenants;

    /** Superusuário: ignora a RLS. */
    JdbcTemplate admin;

    @BeforeEach
    void baseSetUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(environment.getRequiredProperty("spring.datasource.url"), "postgres", "postgres"));
        admin.execute("truncate audit.dispute, audit.case_transition, audit.case_nfe, audit.audit_revision, "
                + "audit.audit_case, audit.policy, quotes.quote_revision, quotes.quote, freight.freight_table, "
                + "freight.component_alias, freight.carrier, documents.document_link, documents.raw_document, "
                + "documents.pending_cancellation, documents.document cascade");
        TenancyFixtures.seedTwoTenants(admin);
        tenants.addCnpj(TwoTenants.A.value(), "45723174");
    }
}

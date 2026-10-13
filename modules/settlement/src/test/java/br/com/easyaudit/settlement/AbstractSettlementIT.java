package br.com.easyaudit.settlement;

import java.util.UUID;
import java.util.concurrent.Callable;

import br.com.easyaudit.audit.fixtures.AuditCaseFixtures;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.tenancy.TenancyFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Base dos testes de integração da liquidação: banco limpo e os dois tenants (A é o dono da raiz 45723174). */
@SpringBootTest(classes = SettlementTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@RecordApplicationEvents
abstract class AbstractSettlementIT {

    /** Transportadora X (raiz 11222333) e Y (raiz 99888777). */
    static final String CARRIER_X = "11222333000181";
    static final String CARRIER_X_BRANCH = "11222333000262";
    static final String CARRIER_Y = "99888777000166";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    Environment environment;

    /** Superusuário: ignora a RLS. */
    JdbcTemplate admin;

    @BeforeEach
    void baseSetUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(environment.getRequiredProperty("spring.datasource.url"),
                "postgres", "postgres"));
        admin.execute("truncate settlement.batch_export, settlement.batch_item, settlement.payment_batch, "
                + "audit.dispute, audit.case_transition, audit.case_nfe, audit.audit_revision, audit.audit_case, "
                + "audit.policy cascade");
        TenancyFixtures.seedTwoTenants(admin);
    }

    <T> T asA(Callable<T> call) throws Exception {
        return TenantContext.callAs(TwoTenants.A, call);
    }

    UUID approved(String carrierCnpj, String name, String charged, String approved) {
        return AuditCaseFixtures.approved(admin, TwoTenants.A.value(), carrierCnpj, name, charged, approved);
    }

    String statusOf(UUID caseId) {
        return admin.queryForObject("select status from audit.audit_case where id = ?", String.class, caseId);
    }
}

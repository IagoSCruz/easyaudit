package br.com.easyaudit.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import br.com.easyaudit.platform.db.TenantRlsChecker;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = AuditTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
class AuditSchemaIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    JdbcTemplate app;
    @Autowired
    TransactionTemplate tx;

    @Test
    void migrationsApplyAndEveryTenantTableHasForcedRls() {
        assertThat(new TenantRlsChecker(new DriverManagerDataSource(jdbcUrl, "easyaudit_owner", "easyaudit_owner")).violations())
                .isEmpty();
        assertThat(app.queryForObject("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'audit' and c.relkind = 'r' and c.relforcerowsecurity""", Integer.class)).isEqualTo(6);
    }

    @Test
    void theApplicationCannotUpdateOrDeleteTransitionsOrRevisions() throws Exception {
        UUID caseId = UUID.randomUUID();
        TenantContext.callAs(TwoTenants.A, () -> tx.execute(s -> {
            app.update("""
                    insert into audit.audit_case (id, tenant_id, cte_document_id, cte_access_key, cte_type, carrier_cnpj, charged_amount)
                    values (?, ?::uuid, gen_random_uuid(), '35261011222333000181570010000001231123456780', 'NORMAL', '11222333000181', 10)""",
                    caseId, TwoTenants.A.toString());
            app.update("""
                    insert into audit.case_transition (id, tenant_id, case_id, from_status, to_status, actor, action)
                    values (gen_random_uuid(), ?::uuid, ?, 'PENDING_AUDIT', 'COMPLIANT', 'SYSTEM', 'AUDIT')""",
                    TwoTenants.A.toString(), caseId);
            app.update("""
                    insert into audit.audit_revision (id, tenant_id, case_id, revision, trigger, classification, charged_amount, snapshot)
                    values (gen_random_uuid(), ?::uuid, ?, 1, 'INITIAL', 'COMPLIANT', 10, '{}')""",
                    TwoTenants.A.toString(), caseId);
            return null;
        }));

        for (String statement : new String[] {
                "update audit.case_transition set note = 'adulterado'",
                "delete from audit.case_transition",
                "update audit.audit_revision set classification = 'DIVERGENT'",
                "delete from audit.audit_revision"}) {
            assertThatThrownBy(() -> TenantContext.callAs(TwoTenants.A, () -> tx.execute(s -> app.update(statement))))
                    .as(statement).rootCause().hasMessageContaining("permission denied");
        }
        // o que é permitido: inserir e ler
        Integer transitions = TenantContext.callAs(TwoTenants.A, () -> tx.execute(s ->
                app.queryForObject("select count(*) from audit.case_transition", Integer.class)));
        assertThat(transitions).isEqualTo(1);
    }

    @Test
    void rlsHidesCasesOfOtherTenants() throws Exception {
        UUID caseId = UUID.randomUUID();
        TenantContext.callAs(TwoTenants.A, () -> tx.execute(s -> app.update("""
                insert into audit.audit_case (id, tenant_id, cte_document_id, cte_access_key, cte_type, carrier_cnpj, charged_amount)
                values (?, ?::uuid, gen_random_uuid(), '35261011222333000181570010000001231123456781', 'NORMAL', '11222333000181', 10)""",
                caseId, TwoTenants.A.toString())));
        Integer visible = TenantContext.callAs(TwoTenants.B, () -> tx.execute(s ->
                app.queryForObject("select count(*) from audit.audit_case", Integer.class)));
        assertThat(visible).isZero();
        assertThatThrownBy(() -> TenantContext.callAs(TwoTenants.B, () -> tx.execute(s -> app.update("""
                insert into audit.audit_case (id, tenant_id, cte_document_id, cte_access_key, cte_type, carrier_cnpj, charged_amount)
                values (gen_random_uuid(), ?::uuid, gen_random_uuid(), '35261011222333000181570010000001231123456782', 'NORMAL', '11222333000181', 10)""",
                TwoTenants.A.toString())))).isInstanceOf(DataAccessException.class);
    }
}

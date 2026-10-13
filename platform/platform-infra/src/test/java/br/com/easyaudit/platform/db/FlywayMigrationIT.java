package br.com.easyaudit.platform.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = FlywayTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
class FlywayMigrationIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    JdbcTemplate app; // easyaudit_app

    JdbcTemplate owner;
    TenantRlsChecker checker;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource(jdbcUrl, "easyaudit_owner", "easyaudit_owner");
        owner = new JdbcTemplate(ds);
        checker = new TenantRlsChecker(ds);
    }

    @AfterEach
    void cleanUp() {
        owner.execute("DROP TABLE IF EXISTS audit.probe");
    }

    @Test
    void modulesSchemasExistAndAppRoleCanUseButNotCreate() {
        List<String> schemas = owner.queryForList("select schema_name from information_schema.schemata", String.class);
        assertThat(schemas).contains("messaging", "tenancy", "documents", "freight", "quotes", "audit", "settlement", "platform");
        assertThat(app.queryForObject("select has_schema_privilege('easyaudit_app', 'messaging', 'USAGE')", Boolean.class)).isTrue();
        assertThat(app.queryForObject("select has_schema_privilege('easyaudit_app', 'messaging', 'CREATE')", Boolean.class)).isFalse();
        assertThatThrownBy(() -> app.execute("CREATE TABLE messaging.hack (id int)")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void tablesCreatedByTheOwnerAreUsableByTheAppRole() {
        owner.execute("CREATE TABLE audit.probe (id int, tenant_id uuid)");
        app.update("INSERT INTO audit.probe (id) VALUES (1)");
        assertThat(app.queryForObject("select count(*) from audit.probe", Integer.class)).isEqualTo(1);
    }

    @Test
    void noTenantTableWithoutRlsInTheMigratedSchema() {
        assertThat(checker.violations()).isEmpty();
    }

    @Test
    void checkerFailsWhenRlsIsMissingOrNotForced() {
        owner.execute("CREATE TABLE audit.probe (id int, tenant_id uuid)");
        assertThat(checker.violations()).containsExactly("audit.probe");

        owner.queryForList("SELECT platform.enable_tenant_rls('audit.probe')");
        assertThat(checker.violations()).isEmpty();

        owner.execute("ALTER TABLE audit.probe NO FORCE ROW LEVEL SECURITY");
        assertThat(checker.violations()).containsExactly("audit.probe");

        owner.execute("ALTER TABLE audit.probe FORCE ROW LEVEL SECURITY");
        owner.execute("DROP POLICY tenant_isolation ON audit.probe");
        assertThat(checker.violations()).containsExactly("audit.probe");
    }

    @Test
    void standardPolicyIsolatesTenants() {
        owner.execute("CREATE TABLE audit.probe (id int, tenant_id uuid)");
        owner.queryForList("SELECT platform.enable_tenant_rls('audit.probe')");
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.support.JdbcTransactionManager(app.getDataSource()));
        tx.executeWithoutResult(s -> {
            app.queryForList("select set_config('app.tenant_id', '11111111-1111-4111-8111-111111111111', true)");
            app.update("INSERT INTO audit.probe VALUES (1, '11111111-1111-4111-8111-111111111111')");
            assertThatThrownBy(() -> app.update("INSERT INTO audit.probe VALUES (2, '22222222-2222-4222-8222-222222222222')"))
                    .isInstanceOf(DataAccessException.class);
        });
        // sem tenant: nenhuma linha visível
        assertThat(app.queryForObject("select count(*) from audit.probe", Integer.class)).isZero();
    }
}

package br.com.easyaudit.quotes;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.easyaudit.platform.db.TenantRlsChecker;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = QuotesTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
class QuotePersistenceIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    JdbcTemplate app;

    @Test
    void migrationsApplyAndBothTablesHaveForcedRls() {
        assertThat(new TenantRlsChecker(new DriverManagerDataSource(jdbcUrl, "easyaudit_owner", "easyaudit_owner")).violations())
                .isEmpty();
        assertThat(app.queryForObject("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'quotes' and c.relname in ('quote', 'quote_revision') and c.relforcerowsecurity""",
                Integer.class)).isEqualTo(2);
    }
}

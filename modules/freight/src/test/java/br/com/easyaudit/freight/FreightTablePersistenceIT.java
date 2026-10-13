package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import br.com.easyaudit.freight.application.CarrierService;
import br.com.easyaudit.freight.domain.StoredFreightTable.Status;
import br.com.easyaudit.freight.infrastructure.persistence.FreightTableRepository;
import br.com.easyaudit.platform.db.TenantRlsChecker;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = FreightTestApplication.class,
        properties = {"spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=false"})
class FreightTablePersistenceIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    FreightTableRepository tables;
    @Autowired
    CarrierService carriers;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate app;

    @BeforeEach
    void clean() {
        new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"))
                .execute("truncate freight.freight_table, freight.component_alias, freight.carrier cascade");
    }

    private <T> T as(TenantId tenant, java.util.function.Supplier<T> action) throws Exception {
        return TenantContext.callAs(tenant, () -> tx.execute(s -> action.get()));
    }

    @Test
    void migrationsApplyAndTheTableHasForcedRls() {
        assertThat(new TenantRlsChecker(new DriverManagerDataSource(jdbcUrl, "easyaudit_owner", "easyaudit_owner")).violations())
                .isEmpty();
        assertThat(app.queryForObject("""
                select relforcerowsecurity from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'freight' and c.relname = 'freight_table'""", Boolean.class)).isTrue();
    }

    @Test
    void insertsAndNumbersVersionsPerCarrier() throws Exception {
        UUID carrier = as(TwoTenants.A, () -> carriers.create("11222333000181", "X").id());
        UUID other = as(TwoTenants.A, () -> carriers.create("45723174000110", "Y").id());

        UUID first = as(TwoTenants.A, () -> tables.insertDraft(carrier, "T1", tables.nextVersion(carrier),
                LocalDate.of(2026, 1, 1), null, false, "{\"routes\":[]}"));
        UUID second = as(TwoTenants.A, () -> tables.insertDraft(carrier, "T2", tables.nextVersion(carrier),
                LocalDate.of(2026, 11, 1), null, false, "{}"));
        int otherVersion = as(TwoTenants.A, () -> tables.nextVersion(other));

        assertThat(as(TwoTenants.A, () -> tables.findById(first)).orElseThrow().version()).isEqualTo(1);
        assertThat(as(TwoTenants.A, () -> tables.findById(second)).orElseThrow().version()).isEqualTo(2);
        assertThat(otherVersion).isEqualTo(1);
        assertThat(as(TwoTenants.A, () -> tables.findById(first)).orElseThrow().status()).isEqualTo(Status.DRAFT);
        assertThat(as(TwoTenants.A, () -> tables.findById(first)).orElseThrow().contentJson()).contains("routes");
    }

    @Test
    void duplicateVersionIsRejectedByTheDatabase() throws Exception {
        UUID carrier = as(TwoTenants.A, () -> carriers.create("11222333000181", "X").id());
        as(TwoTenants.A, () -> tables.insertDraft(carrier, "T1", 1, LocalDate.of(2026, 1, 1), null, false, "{}"));
        assertThatThrownBy(() -> as(TwoTenants.A, () -> tables.insertDraft(carrier, "T1b", 1,
                LocalDate.of(2026, 1, 1), null, false, "{}"))).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rlsHidesTablesOfOtherTenants() throws Exception {
        UUID carrier = as(TwoTenants.A, () -> carriers.create("11222333000181", "X").id());
        UUID table = as(TwoTenants.A, () -> tables.insertDraft(carrier, "T1", 1, LocalDate.of(2026, 1, 1), null, false, "{}"));

        assertThat(as(TwoTenants.B, () -> tables.findById(table))).isEmpty();
        assertThat(as(TwoTenants.B, () -> tables.search(null, null))).isEmpty();
        assertThat(as(TwoTenants.A, () -> tables.search(null, null))).hasSize(1);
    }
}

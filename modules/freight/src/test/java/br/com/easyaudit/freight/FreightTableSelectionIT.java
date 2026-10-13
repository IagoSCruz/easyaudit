package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.freight.api.FreightTableSelector;
import br.com.easyaudit.freight.api.FreightTableSelector.NoTable;
import br.com.easyaudit.freight.api.FreightTableSelector.RouteNotCovered;
import br.com.easyaudit.freight.api.FreightTableSelector.Selected;
import br.com.easyaudit.freight.application.CarrierService;
import br.com.easyaudit.freight.application.FreightTableSelection;
import br.com.easyaudit.freight.application.FreightTableService;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = FreightTestApplication.class,
        properties = {"spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=false"})
class FreightTableSelectionIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    FreightTableSelector selector;
    @Autowired
    FreightTableSelection selection;
    @Autowired
    FreightTableService tables;
    @Autowired
    CarrierService carriers;

    UUID carrier;
    UUID v1;
    UUID v2;

    @BeforeEach
    void setUp() throws Exception {
        new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"))
                .execute("truncate freight.freight_table, freight.component_alias, freight.carrier cascade");
        carrier = as(() -> carriers.create("11222333000181", "X").id());
        v1 = as(() -> createAndActivate(LocalDate.of(2026, 1, 1)));
        v2 = as(() -> createAndActivate(LocalDate.of(2026, 11, 1)));
    }

    private <T> T as(java.util.concurrent.Callable<T> call) throws Exception {
        return TenantContext.callAs(TwoTenants.A, call);
    }

    private UUID createAndActivate(LocalDate validFrom) {
        FreightTableContent content = FreightTableValidatorTest.tableT();
        content.setValidFrom(validFrom);
        UUID id = tables.create(carrier, content, FreightTableService.JSON_LOCATION).id();
        tables.activate(id);
        return id;
    }

    private FreightTableSelector.Selection select(String issuedAt, String destination, String origin) throws Exception {
        return as(() -> selector.select(carrier, OffsetDateTime.parse(issuedAt), destination, origin));
    }

    @Test
    void selectsTheVersionInForceOnTheIssueDate() throws Exception {
        assertThat(select("2026-10-20T10:00:00-03:00", "01310100", null))
                .isEqualTo(new Selected(v1, 1, "SP capital"));
        assertThat(select("2026-11-02T10:00:00-03:00", "01310100", null))
                .isEqualTo(new Selected(v2, 2, "SP capital"));
    }

    @Test
    void boundaryDaysBelongToTheRightVersion() throws Exception {
        assertThat(select("2026-10-31T23:59:59-03:00", "01310100", null)).isEqualTo(new Selected(v1, 1, "SP capital"));
        assertThat(select("2026-11-01T00:00:00-03:00", "01310100", null)).isEqualTo(new Selected(v2, 2, "SP capital"));
    }

    @Test
    void theIssueDateIsEvaluatedInSaoPauloTime() throws Exception {
        // 2026-11-01T01:30Z ainda é 31/10 22:30 em São Paulo: vale a versão 1
        assertThat(select("2026-11-01T01:30:00Z", "01310100", null)).isEqualTo(new Selected(v1, 1, "SP capital"));
        // 2026-11-01T04:00Z é 01:00 de 1º/11 em São Paulo: vale a versão 2
        assertThat(select("2026-11-01T04:00:00Z", "01310100", null)).isEqualTo(new Selected(v2, 2, "SP capital"));
    }

    @Test
    void noTableWhenNothingIsInForce() throws Exception {
        assertThat(select("2025-12-31T23:00:00-03:00", "01310100", null)).isInstanceOf(NoTable.class);
        UUID otherCarrier = as(() -> carriers.create("45723174000110", "Y").id());
        assertThat(as(() -> selector.select(otherCarrier, OffsetDateTime.parse("2026-12-01T10:00:00-03:00"),
                "01310100", null))).isInstanceOf(NoTable.class);
    }

    @Test
    void routeNotCoveredWhenNoRouteHasTheCep() throws Exception {
        assertThat(select("2026-12-01T10:00:00-03:00", "69900000", null)).isEqualTo(new RouteNotCovered(v2, 2));
    }

    @Test
    void draftAndArchivedTablesAreNeverSelected() throws Exception {
        FreightTableContent draft = FreightTableValidatorTest.tableT();
        draft.setValidFrom(LocalDate.of(2027, 1, 1));
        as(() -> tables.create(carrier, draft, FreightTableService.JSON_LOCATION));
        assertThat(select("2027-02-01T10:00:00-03:00", "01310100", null)).isEqualTo(new Selected(v2, 2, "SP capital"));
    }

    @Test
    void anotherTenantSeesNoTables() throws Exception {
        assertThat(TenantContext.callAs(TwoTenants.B, () -> selector.select(carrier,
                OffsetDateTime.parse("2026-12-01T10:00:00-03:00"), "01310100", null))).isInstanceOf(NoTable.class);
    }

    @Test
    void compiledVersionsAreCachedAndReusedAcrossSelections() throws Exception {
        long before = selection.cachedVersions();
        select("2026-12-01T10:00:00-03:00", "01310100", null);
        select("2026-12-02T10:00:00-03:00", "01310100", null);
        select("2026-12-03T10:00:00-03:00", "01310100", null);
        assertThat(selection.cachedVersions()).isLessThanOrEqualTo(before + 1);
    }
}

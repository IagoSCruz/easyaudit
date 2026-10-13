package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.freight.api.FreightCalculation;
import br.com.easyaudit.freight.api.FreightCalculation.Calculated;
import br.com.easyaudit.freight.api.FreightCalculation.NoTable;
import br.com.easyaudit.freight.api.FreightCalculation.RouteNotCovered;
import br.com.easyaudit.freight.api.FreightCalculation.Shipment;
import br.com.easyaudit.freight.application.CarrierService;
import br.com.easyaudit.freight.application.FreightTableService;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = FreightTestApplication.class,
        properties = {"spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=false"})
@AutoConfigureMockMvc
class FreightSimulationIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper mapper;
    @Autowired
    FreightTableService tables;
    @Autowired
    CarrierService carriers;
    @Autowired
    FreightCalculation calculation;

    static final String ADMIN = TwoTenants.bearerA("TENANT_ADMIN");
    JdbcTemplate admin;
    UUID carrier;

    @BeforeEach
    void setUp() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate freight.freight_table, freight.component_alias, freight.carrier cascade");
        carrier = TenantContext.callAs(TwoTenants.A, () -> carriers.create("11222333000181", "X").id());
    }

    private UUID table(boolean activate) throws Exception {
        return TenantContext.callAs(TwoTenants.A, () -> {
            UUID id = tables.create(carrier, FreightTableValidatorTest.tableT(), FreightTableService.JSON_LOCATION).id();
            if (activate) {
                tables.activate(id);
            }
            return id;
        });
    }

    private String shipment(String dest, String actual, String volume, String cargo, String icms) {
        return "{\"destinationCep\":\"" + dest + "\",\"actualWeightKg\":" + actual
                + (volume == null ? "" : ",\"volumeM3\":" + volume) + ",\"cargoValue\":" + cargo
                + (icms == null ? "" : ",\"icmsRatePercent\":" + icms) + "}";
    }

    @Test
    void simulatingADraftReturnsTheBreakdownAndChangesNothing() throws Exception {
        UUID id = table(false);
        String before = admin.queryForObject("select updated_at::text || status from freight.freight_table where id = ?", String.class, id);

        mvc.perform(post("/api/v1/freight-tables/" + id + "/simulations").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(shipment("01310100", "12.5", "0.05", "1000", "12")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CALCULATED"))
                .andExpect(jsonPath("$.tableVersion").value(1))
                .andExpect(jsonPath("$.routeName").value("SP capital"))
                .andExpect(jsonPath("$.cubedWeightKg").value(15.0))
                .andExpect(jsonPath("$.chargeableWeightKg").value(15.0))
                .andExpect(jsonPath("$.subtotal").value(58.1))
                .andExpect(jsonPath("$.total").value(66.02))
                .andExpect(jsonPath("$.icmsAdded").value(true))
                .andExpect(jsonPath("$.components[?(@.code=='ICMS')].amount").value(7.92))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        assertThat(admin.queryForObject("select updated_at::text || status from freight.freight_table where id = ?", String.class, id))
                .isEqualTo(before);
        assertThat(admin.queryForObject("select count(*) from freight.freight_table", Integer.class)).isEqualTo(1);
    }

    @Test
    void anActiveTableCanBeSimulatedToo() throws Exception {
        UUID id = table(true);
        mvc.perform(post("/api/v1/freight-tables/" + id + "/simulations").header("Authorization", TwoTenants.bearerA("VIEWER"))
                        .contentType(MediaType.APPLICATION_JSON).content(shipment("01310100", "1", null, "50", null)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(40.0))
                .andExpect(jsonPath("$.components[?(@.code=='MINIMUM_ADJUSTMENT')].amount").value(8.4));
    }

    @Test
    void routeNotCoveredHasNoComponents() throws Exception {
        UUID id = table(false);
        mvc.perform(post("/api/v1/freight-tables/" + id + "/simulations").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(shipment("69900000", "5", null, "100", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ROUTE_NOT_COVERED"))
                .andExpect(jsonPath("$.components.length()").value(0))
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void invalidShipmentIs422NamingTheField() throws Exception {
        UUID id = table(false);
        mvc.perform(post("/api/v1/freight-tables/" + id + "/simulations").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(shipment("01310100", "-1", null, "100", null)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_SHIPMENT"))
                .andExpect(jsonPath("$.errors[0].field").value("actualWeightKg"));
    }

    @Test
    void archivedAndForeignTablesCannotBeSimulated() throws Exception {
        UUID id = table(false);
        TenantContext.callAs(TwoTenants.A, () -> tables.archive(id));
        mvc.perform(post("/api/v1/freight-tables/" + id + "/simulations").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(shipment("01310100", "5", null, "100", null)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/freight-tables/" + id + "/simulations").header("Authorization", TwoTenants.bearerB("TENANT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(shipment("01310100", "5", null, "100", null)))
                .andExpect(status().isNotFound());
    }

    @Test
    void theApiFacadeSelectsTheTableAndCalculates() throws Exception {
        table(true); // vigente desde 2026-01-01
        Shipment shipment = new Shipment("01310100", null, new BigDecimal("12.5"), null, new BigDecimal("0.05"),
                new BigDecimal("1000"), new BigDecimal("12"));

        var ok = TenantContext.callAs(TwoTenants.A, () -> calculation.calculate(carrier,
                OffsetDateTime.parse("2026-10-20T10:00:00-03:00"), shipment));
        assertThat(ok).isInstanceOf(Calculated.class);
        assertThat(((Calculated) ok).total()).isEqualByComparingTo("66.02");
        assertThat(((Calculated) ok).amountOf("TOLL")).isEqualByComparingTo("3.10");

        assertThat(TenantContext.callAs(TwoTenants.A, () -> calculation.calculate(carrier,
                OffsetDateTime.parse("2025-12-31T10:00:00-03:00"), shipment))).isInstanceOf(NoTable.class);
        assertThat(TenantContext.callAs(TwoTenants.A, () -> calculation.calculate(carrier,
                OffsetDateTime.parse("2026-10-20T10:00:00-03:00"),
                new Shipment("69900000", null, new BigDecimal("5"), null, null, new BigDecimal("100"), null))))
                .isInstanceOf(RouteNotCovered.class);
    }

    @Test
    void theFacadeRejectsInvalidInputs() {
        Shipment bad = new Shipment("01310100", null, new BigDecimal("-1"), null, null, new BigDecimal("100"), null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TenantContext.callAs(TwoTenants.A, () -> calculation.calculate(
                carrier, OffsetDateTime.parse("2026-10-20T10:00:00-03:00"), bad)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actualWeightKg");
        assertThat(LocalDate.now()).isNotNull();
    }
}

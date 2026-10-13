package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
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
class FreightTableCreationIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper mapper;

    JdbcTemplate admin;
    String carrierId;
    static final String ADMIN = TwoTenants.bearerA("TENANT_ADMIN");

    @BeforeEach
    void setUp() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate freight.freight_table, freight.component_alias, freight.carrier cascade");
        String response = mvc.perform(post("/api/v1/carriers").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cnpj\":\"11222333000181\",\"name\":\"X\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        carrierId = JsonPath.read(response, "$.id");
    }

    private String body(FreightTableContent content) {
        return "{\"carrierId\":\"" + carrierId + "\",\"content\":" + mapper.writeValueAsString(content) + "}";
    }

    @Test
    void validTableIsCreatedAsDraftWithVersion1() throws Exception {
        mvc.perform(post("/api/v1/freight-tables").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body(FreightTableValidatorTest.tableT())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.name").value("Tabela T"))
                .andExpect(jsonPath("$.content.routes[0].weightBands.length()").value(3))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        assertThat(admin.queryForObject("select count(*) from freight.freight_table", Integer.class)).isEqualTo(1);
    }

    @Test
    void eachNewTableOfTheCarrierGetsTheNextVersion() throws Exception {
        for (int i = 1; i <= 2; i++) {
            mvc.perform(post("/api/v1/freight-tables").header("Authorization", ADMIN)
                            .contentType(MediaType.APPLICATION_JSON).content(body(FreightTableValidatorTest.tableT())))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(i));
        }
    }

    @Test
    void invalidCepAndPercentageAreBothReportedAndNothingIsCreated() throws Exception {
        FreightTableContent table = FreightTableValidatorTest.tableT();
        table.getRoutes().get(0).setDestinationCepFrom("0100000");
        table.getRoutes().get(0).setAdValoremPercent(new BigDecimal("150"));

        mvc.perform(post("/api/v1/freight-tables").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(body(table)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[?(@.field=='content.routes[0].destinationCepFrom')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='content.routes[0].adValoremPercent')]").exists())
                .andExpect(OpenApiResponseValidator.conformsToContract());
        assertThat(admin.queryForObject("select count(*) from freight.freight_table", Integer.class)).isZero();
    }

    @Test
    void onlyTenantAdminsCreateAndAnyRoleReads() throws Exception {
        mvc.perform(post("/api/v1/freight-tables").header("Authorization", TwoTenants.bearerA("AUDITOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(FreightTableValidatorTest.tableT())))
                .andExpect(status().isForbidden());
        String id = JsonPath.read(mvc.perform(post("/api/v1/freight-tables").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body(FreightTableValidatorTest.tableT())))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String viewer = TwoTenants.bearerA("VIEWER");
        mvc.perform(get("/api/v1/freight-tables/" + id).header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/freight-tables").header("Authorization", viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").doesNotExist())
                .andExpect(OpenApiResponseValidator.conformsToContract());
        mvc.perform(get("/api/v1/freight-tables").param("status", "ACTIVE").header("Authorization", viewer))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownCarrierIs404AndCarriersOfOtherTenantsAreInvisible() throws Exception {
        String content = mapper.writeValueAsString(FreightTableValidatorTest.tableT());
        mvc.perform(post("/api/v1/freight-tables").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierId\":\"" + UUID.randomUUID() + "\",\"content\":" + content + "}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/freight-tables").header("Authorization", TwoTenants.bearerB("TENANT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body(FreightTableValidatorTest.tableT())))
                .andExpect(status().isNotFound());
    }

    @Test
    void tablesOfAnotherTenantAreInvisible() throws Exception {
        String id = JsonPath.read(mvc.perform(post("/api/v1/freight-tables").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(body(FreightTableValidatorTest.tableT())))
                .andReturn().getResponse().getContentAsString(), "$.id");
        String adminB = TwoTenants.bearerB("TENANT_ADMIN");
        mvc.perform(get("/api/v1/freight-tables/" + id).header("Authorization", adminB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/freight-tables").header("Authorization", adminB)).andExpect(jsonPath("$.length()").value(0));
    }
}

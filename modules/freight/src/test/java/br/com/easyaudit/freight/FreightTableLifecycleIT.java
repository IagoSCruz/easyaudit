package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class FreightTableLifecycleIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JsonMapper mapper;

    static final String ADMIN = TwoTenants.bearerA("TENANT_ADMIN");
    JdbcTemplate admin;
    String carrierId;

    @BeforeEach
    void setUp() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate freight.freight_table, freight.component_alias, freight.carrier cascade");
        carrierId = JsonPath.read(mvc.perform(post("/api/v1/carriers").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cnpj\":\"11222333000181\",\"name\":\"X\"}"))
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private String createDraft() throws Exception {
        String body = "{\"carrierId\":\"" + carrierId + "\",\"content\":" + mapper.writeValueAsString(FreightTableValidatorTest.tableT()) + "}";
        return JsonPath.read(mvc.perform(post("/api/v1/freight-tables").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");
    }

    private void forceStatus(String id, String status) {
        admin.update("update freight.freight_table set status = ?, activated_at = case when ? = 'ACTIVE' then now() end where id = ?::uuid",
                status, status, id);
    }

    private String update(FreightTableContent content) {
        return "{\"content\":" + mapper.writeValueAsString(content) + "}";
    }

    @Test
    void aDraftCanBeEdited() throws Exception {
        String id = createDraft();
        FreightTableContent changed = FreightTableValidatorTest.tableT();
        changed.setName("Tabela T editada");
        changed.setDispatchFee(new BigDecimal("6.00"));

        mvc.perform(put("/api/v1/freight-tables/" + id).header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(update(changed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tabela T editada"))
                .andExpect(jsonPath("$.content.dispatchFee").value(6.0))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void editingValidatesLikeCreating() throws Exception {
        String id = createDraft();
        FreightTableContent bad = FreightTableValidatorTest.tableT();
        bad.getRoutes().get(0).setGrisPercent(new BigDecimal("101"));
        mvc.perform(put("/api/v1/freight-tables/" + id).header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(update(bad)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("content.routes[0].grisPercent"));
    }

    @Test
    void anActiveTableCannotBeChangedOrArchived() throws Exception {
        String id = createDraft();
        forceStatus(id, "ACTIVE");
        String before = admin.queryForObject("select content::text from freight.freight_table where id = ?::uuid", String.class, id);

        FreightTableContent changed = FreightTableValidatorTest.tableT();
        changed.setName("outra");
        mvc.perform(put("/api/v1/freight-tables/" + id).header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(update(changed)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_NOT_EDITABLE"));
        mvc.perform(post("/api/v1/freight-tables/" + id + "/archive").header("Authorization", ADMIN))
                .andExpect(status().isConflict());

        assertThat(admin.queryForObject("select content::text from freight.freight_table where id = ?::uuid", String.class, id))
                .isEqualTo(before);
        assertThat(admin.queryForObject("select status from freight.freight_table where id = ?::uuid", String.class, id))
                .isEqualTo("ACTIVE");
    }

    @Test
    void newVersionFromAnActiveTableCopiesTheContentAndIncrementsTheVersion() throws Exception {
        String id = createDraft();
        forceStatus(id, "ACTIVE");

        mvc.perform(post("/api/v1/freight-tables/" + id + "/new-version").header("Authorization", ADMIN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.name").value("Tabela T"))
                .andExpect(jsonPath("$.content.routes[0].weightBands.length()").value(3))
                .andExpect(jsonPath("$.activatedAt").doesNotExist())
                .andExpect(OpenApiResponseValidator.conformsToContract());
        assertThat(admin.queryForObject("select count(*) from freight.freight_table", Integer.class)).isEqualTo(2);
    }

    @Test
    void aDraftCanBeArchivedAndThenIsFrozen() throws Exception {
        String id = createDraft();
        mvc.perform(post("/api/v1/freight-tables/" + id + "/archive").header("Authorization", ADMIN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mvc.perform(put("/api/v1/freight-tables/" + id).header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(update(FreightTableValidatorTest.tableT())))
                .andExpect(status().isConflict());
        // a nova versão de uma arquivada também é permitida
        mvc.perform(post("/api/v1/freight-tables/" + id + "/new-version").header("Authorization", ADMIN))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version").value(2));
    }

    @Test
    void newVersionOfADraftIsRefused() throws Exception {
        String id = createDraft();
        mvc.perform(post("/api/v1/freight-tables/" + id + "/new-version").header("Authorization", ADMIN))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TABLE_IS_DRAFT"));
    }

    @Test
    void permissionsAndMissingTables() throws Exception {
        String id = createDraft();
        String viewer = TwoTenants.bearerA("VIEWER");
        mvc.perform(post("/api/v1/freight-tables/" + id + "/archive").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/freight-tables/" + id).header("Authorization", viewer)
                .contentType(MediaType.APPLICATION_JSON).content(update(FreightTableValidatorTest.tableT()))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/freight-tables/" + UUID.randomUUID() + "/archive").header("Authorization", ADMIN))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/freight-tables/" + id + "/archive").header("Authorization", TwoTenants.bearerB("TENANT_ADMIN")))
                .andExpect(status().isNotFound());
    }
}

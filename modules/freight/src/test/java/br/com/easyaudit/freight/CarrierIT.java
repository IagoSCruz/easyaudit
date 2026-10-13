package br.com.easyaudit.freight;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@SpringBootTest(classes = FreightTestApplication.class, properties = {"spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=false"})
@AutoConfigureMockMvc
class CarrierIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;

    private static final String ADMIN = TwoTenants.bearerA("TENANT_ADMIN");

    @BeforeEach
    void clean() {
        new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"))
                .execute("truncate freight.component_alias, freight.carrier cascade");
    }

    private String createCarrier(String cnpj, String name) throws Exception {
        return mvc.perform(post("/api/v1/carriers").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cnpj\":\"" + cnpj + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void adminRegistersACarrierManually() throws Exception {
        mvc.perform(post("/api/v1/carriers").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cnpj\":\"11.222.333/0001-81\",\"name\":\"Transportes Rápidos\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cnpj").value("11222333000181"))
                .andExpect(jsonPath("$.name").value("Transportes Rápidos"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.autoRegistered").value(false))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void repeatedCnpjInTheTenantIs409ButAnotherTenantCanRegisterIt() throws Exception {
        createCarrier("11222333000181", "Rápidos");
        mvc.perform(post("/api/v1/carriers").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cnpj\":\"11222333000181\",\"name\":\"Outra\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CARRIER_CNPJ_TAKEN"));

        mvc.perform(post("/api/v1/carriers").header("Authorization", TwoTenants.bearerB("TENANT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cnpj\":\"11222333000181\",\"name\":\"Rápidos B\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void invalidCnpjIs422() throws Exception {
        mvc.perform(post("/api/v1/carriers").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cnpj\":\"11222333000182\",\"name\":\"X\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INVALID_CNPJ"));
    }

    @Test
    void onlyTenantAdminsChangeThingsButAnyRoleReads() throws Exception {
        String id = JsonPath.read(createCarrier("11222333000181", "Rápidos"), "$.id");
        String viewer = TwoTenants.bearerA("VIEWER");

        mvc.perform(post("/api/v1/carriers").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cnpj\":\"45723174000110\",\"name\":\"X\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/carriers/" + id).header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/carriers").header("Authorization", viewer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/carriers/" + id).header("Authorization", viewer)).andExpect(status().isOk());
    }

    @Test
    void editAndInactivate() throws Exception {
        String id = JsonPath.read(createCarrier("11222333000181", "Rápidos"), "$.id");
        mvc.perform(patch("/api/v1/carriers/" + id).header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rápidos S.A.\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Rápidos S.A."))
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/api/v1/carriers").param("active", "true").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/carriers").param("active", "false").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void carriersOfAnotherTenantAreInvisible() throws Exception {
        String id = JsonPath.read(createCarrier("11222333000181", "Rápidos"), "$.id");
        String adminB = TwoTenants.bearerB("TENANT_ADMIN");
        mvc.perform(get("/api/v1/carriers/" + id).header("Authorization", adminB)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/carriers/" + id).header("Authorization", adminB).contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/carriers").header("Authorization", adminB)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void componentAliasesCrud() throws Exception {
        String id = JsonPath.read(createCarrier("11222333000181", "Rápidos"), "$.id");
        String base = "/api/v1/carriers/" + id + "/component-aliases";

        String created = mvc.perform(post(base).header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"SEG. CARGA\",\"componentCode\":\"AD_VALOREM\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.componentCode").value("AD_VALOREM"))
                .andExpect(OpenApiResponseValidator.conformsToContract())
                .andReturn().getResponse().getContentAsString();
        String aliasId = JsonPath.read(created, "$.id");

        // o mesmo nome escrito de outro jeito colide
        mvc.perform(post(base).header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"seg carga\",\"componentCode\":\"GRIS\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALIAS_ALREADY_MAPPED"));

        mvc.perform(get(base).header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].alias").value("SEG. CARGA"));

        mvc.perform(delete(base + "/" + aliasId).header("Authorization", ADMIN)).andExpect(status().isNoContent());
        mvc.perform(delete(base + "/" + aliasId).header("Authorization", ADMIN)).andExpect(status().isNotFound());
        mvc.perform(get(base).header("Authorization", ADMIN)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aliasValidationAndPermissions() throws Exception {
        String id = JsonPath.read(createCarrier("11222333000181", "Rápidos"), "$.id");
        String base = "/api/v1/carriers/" + id + "/component-aliases";
        mvc.perform(post(base).header("Authorization", TwoTenants.bearerA("AUDITOR")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"X\",\"componentCode\":\"GRIS\"}")).andExpect(status().isForbidden());
        mvc.perform(post(base).header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"X\",\"componentCode\":\"NAO_EXISTE\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(base).header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"---\",\"componentCode\":\"GRIS\"}")).andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/v1/carriers/" + java.util.UUID.randomUUID() + "/component-aliases")
                        .header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\":\"X\",\"componentCode\":\"GRIS\"}"))
                .andExpect(status().isNotFound());
    }
}

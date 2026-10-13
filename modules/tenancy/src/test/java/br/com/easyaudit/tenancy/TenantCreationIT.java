package br.com.easyaudit.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = TenancyTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class TenantCreationIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from tenancy.tenant_cnpj");
        jdbc.update("delete from tenancy.cnpj_root");
        jdbc.update("delete from tenancy.tenant");
    }

    private static String admin() {
        return TestTokens.bearer(null, "PLATFORM_ADMIN");
    }

    private static String body(String slug, String... cnpjs) {
        String list = String.join(",", java.util.Arrays.stream(cnpjs).map(c -> "\"" + c + "\"").toList());
        return "{\"name\":\"Loja Exemplo\",\"slug\":\"" + slug + "\",\"cnpjs\":[" + list + "]}";
    }

    @Test
    void platformAdminCreatesTenant() throws Exception {
        mvc.perform(post("/admin/v1/tenants").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body("loja-exemplo", "11.222.333/0001-81")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.slug").value("loja-exemplo"))
                .andExpect(jsonPath("$.cnpjs[0]").value("11222333000181"));

        assertThat(jdbc.queryForObject("select count(*) from tenancy.tenant where slug = 'loja-exemplo'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select kind from tenancy.tenant_cnpj", String.class)).isEqualTo("FULL");
    }

    @Test
    void createdTenantCanBeFetched() throws Exception {
        String response = mvc.perform(post("/admin/v1/tenants").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body("loja-b", "11222333")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(response, "$.id");

        mvc.perform(get("/admin/v1/tenants/" + id).header("Authorization", admin()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.cnpjs[0]").value("11222333"));
        assertThat(jdbc.queryForObject("select kind from tenancy.tenant_cnpj", String.class)).isEqualTo("ROOT");
    }

    @Test
    void duplicateSlugIs409AndCreatesNothing() throws Exception {
        mvc.perform(post("/admin/v1/tenants").header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content(body("duplicada", "11222333000181")))
                .andExpect(status().isCreated());

        mvc.perform(post("/admin/v1/tenants").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body("duplicada", "12ABC34501DE35")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TENANT_SLUG_TAKEN"));

        assertThat(jdbc.queryForObject("select count(*) from tenancy.tenant", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenancy.tenant_cnpj", Integer.class)).isEqualTo(1);
    }

    @Test
    void cnpjRootOfAnotherTenantIs409AndTenantIsNotCreated() throws Exception {
        mvc.perform(post("/admin/v1/tenants").header("Authorization", admin())
                .contentType(MediaType.APPLICATION_JSON).content(body("primeira", "11222333000181")))
                .andExpect(status().isCreated());

        // mesma raiz (11222333), outra filial
        mvc.perform(post("/admin/v1/tenants").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body("segunda", "11.222.333")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CNPJ_OWNED_BY_OTHER_TENANT"));

        assertThat(jdbc.queryForObject("select count(*) from tenancy.tenant", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidCheckDigitIs422() throws Exception {
        mvc.perform(post("/admin/v1/tenants").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body("invalida", "11.222.333/0001-82")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_CNPJ"));
        assertThat(jdbc.queryForObject("select count(*) from tenancy.tenant", Integer.class)).isZero();
    }

    @Test
    void missingFieldsAre422() throws Exception {
        mvc.perform(post("/admin/v1/tenants").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"X\",\"slug\":\"Slug Inválido\",\"cnpjs\":[]}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void userWithoutPlatformRoleIs403() throws Exception {
        String tenantAdmin = TestTokens.bearer("11111111-1111-4111-8111-111111111111", "TENANT_ADMIN");
        mvc.perform(post("/admin/v1/tenants").header("Authorization", tenantAdmin)
                        .contentType(MediaType.APPLICATION_JSON).content(body("loja-x", "11222333000181")))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select count(*) from tenancy.tenant", Integer.class)).isZero();
    }
}

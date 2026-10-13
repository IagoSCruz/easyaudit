package br.com.easyaudit.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TestTokens;
import br.com.easyaudit.tenancy.application.TenantService;
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
class TenantCnpjIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TenantService tenants;

    UUID tenantA;
    UUID tenantB;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from tenancy.tenant_cnpj");
        jdbc.update("delete from tenancy.cnpj_root");
        jdbc.update("delete from tenancy.tenant");
        tenantA = tenants.create("Loja A", "loja-a", java.util.List.of("11222333000181")).id();
        tenantB = tenants.create("Loja B", "loja-b", java.util.List.of("99888777")).id();
    }

    private String adminOf(UUID tenant) {
        return TestTokens.bearer(tenant.toString(), "TENANT_ADMIN");
    }

    private static String cnpj(String value) {
        return "{\"cnpj\":\"" + value + "\"}";
    }

    @Test
    void adminAddsAlphanumericCnpj() throws Exception {
        mvc.perform(post("/api/v1/tenant/cnpjs").header("Authorization", adminOf(tenantA))
                        .contentType(MediaType.APPLICATION_JSON).content(cnpj("12.ABC.345/01DE-35")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cnpj").value("12ABC34501DE35"))
                .andExpect(jsonPath("$.kind").value("FULL"));

        mvc.perform(get("/api/v1/tenant/cnpjs").header("Authorization", adminOf(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void adminAddsRoot() throws Exception {
        mvc.perform(post("/api/v1/tenant/cnpjs").header("Authorization", adminOf(tenantA))
                        .contentType(MediaType.APPLICATION_JSON).content(cnpj("12ABC345")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.kind").value("ROOT"));
    }

    @Test
    void invalidCheckDigitIs422() throws Exception {
        mvc.perform(post("/api/v1/tenant/cnpjs").header("Authorization", adminOf(tenantA))
                        .contentType(MediaType.APPLICATION_JSON).content(cnpj("11.222.333/0001-82")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_CNPJ"))
                .andExpect(jsonPath("$.errors[0].field").value("cnpj"));
    }

    @Test
    void cnpjOrRootOfAnotherTenantIs409() throws Exception {
        // filial com a mesma raiz do tenant B (99888777)
        mvc.perform(post("/api/v1/tenant/cnpjs").header("Authorization", adminOf(tenantA))
                        .contentType(MediaType.APPLICATION_JSON).content(cnpj("99888777")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CNPJ_OWNED_BY_OTHER_TENANT"));
        assertThat(jdbc.queryForObject("select count(*) from tenancy.tenant_cnpj where tenant_id = ?", Integer.class,
                tenantA)).isEqualTo(1);
    }

    @Test
    void adminRemovesCnpjAndReleasesTheRoot() throws Exception {
        mvc.perform(delete("/api/v1/tenant/cnpjs/11222333000181").header("Authorization", adminOf(tenantA)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from tenancy.cnpj_root where root = '11222333'", Integer.class))
                .isZero();

        // a raiz liberada pode ser registrada por outro tenant
        mvc.perform(post("/api/v1/tenant/cnpjs").header("Authorization", adminOf(tenantB))
                        .contentType(MediaType.APPLICATION_JSON).content(cnpj("11222333")))
                .andExpect(status().isCreated());
    }

    @Test
    void removingAnUnknownCnpjIs404() throws Exception {
        mvc.perform(delete("/api/v1/tenant/cnpjs/11222333000181").header("Authorization", adminOf(tenantB)))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonAdminRolesCanReadButNotChange() throws Exception {
        String viewer = TestTokens.bearer(tenantA.toString(), "VIEWER");
        mvc.perform(get("/api/v1/tenant/cnpjs").header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/tenant/cnpjs").header("Authorization", viewer)
                .contentType(MediaType.APPLICATION_JSON).content(cnpj("12ABC345"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/tenant/cnpjs/11222333000181").header("Authorization", viewer))
                .andExpect(status().isForbidden());
    }

    @Test
    void listingShowsOnlyTheCallersTenant() throws Exception {
        mvc.perform(get("/api/v1/tenant/cnpjs").header("Authorization", adminOf(tenantB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cnpj").value("99888777"));
    }
}

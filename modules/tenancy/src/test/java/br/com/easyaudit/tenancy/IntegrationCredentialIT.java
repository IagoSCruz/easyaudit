package br.com.easyaudit.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TestTokens;
import br.com.easyaudit.tenancy.application.TenantService;
import com.jayway.jsonpath.JsonPath;
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
class IntegrationCredentialIT {

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
        jdbc.update("delete from tenancy.integration_credential");
        jdbc.update("delete from tenancy.tenant_cnpj");
        jdbc.update("delete from tenancy.cnpj_root");
        jdbc.update("delete from tenancy.tenant");
        tenantA = tenants.create("Loja A", "loja-a", List.of("11222333000181")).id();
        tenantB = tenants.create("Loja B", "loja-b", List.of("99888777")).id();
    }

    private String admin(UUID tenant) {
        return TestTokens.bearer(tenant.toString(), "TENANT_ADMIN");
    }

    private String create(UUID tenant, String description) throws Exception {
        return mvc.perform(post("/api/v1/tenant/integration-credentials").header("Authorization", admin(tenant))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"" + description + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void creationShowsTheSecretOnceAndStoresItEncrypted() throws Exception {
        String response = create(tenantA, "Webhook Intelipost");
        String keyId = JsonPath.read(response, "$.keyId");
        String secret = JsonPath.read(response, "$.secret");
        assertThat(keyId).startsWith("ea_");
        assertThat(secret).hasSizeGreaterThanOrEqualTo(40);
        assertThat((String) JsonPath.read(response, "$.status")).isEqualTo("ACTIVE");

        byte[] stored = jdbc.queryForObject("select secret_ciphertext from tenancy.integration_credential where key_id = ?",
                byte[].class, keyId);
        assertThat(new String(stored)).doesNotContain(secret);
        assertThat(jdbc.queryForObject("select key_version from tenancy.integration_credential", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void listingNeverReturnsTheSecret() throws Exception {
        String secret = JsonPath.read(create(tenantA, "Webhook"), "$.secret");

        mvc.perform(get("/api/v1/tenant/integration-credentials").header("Authorization", admin(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].keyId").isNotEmpty())
                .andExpect(jsonPath("$[0].description").value("Webhook"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty())
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].secret").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(secret))));
    }

    @Test
    void revokedCredentialBecomesRevoked() throws Exception {
        String keyId = JsonPath.read(create(tenantA, "Webhook"), "$.keyId");

        mvc.perform(post("/api/v1/tenant/integration-credentials/" + keyId + "/revoke")
                        .header("Authorization", admin(tenantA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        assertThat(jdbc.queryForObject("select status from tenancy.integration_credential where key_id = ?",
                String.class, keyId)).isEqualTo("REVOKED");

        // revogar de novo é idempotente
        mvc.perform(post("/api/v1/tenant/integration-credentials/" + keyId + "/revoke")
                .header("Authorization", admin(tenantA))).andExpect(status().isOk());
    }

    @Test
    void credentialOfAnotherTenantIs404() throws Exception {
        String keyId = JsonPath.read(create(tenantA, "Webhook"), "$.keyId");

        mvc.perform(post("/api/v1/tenant/integration-credentials/" + keyId + "/revoke")
                        .header("Authorization", admin(tenantB)))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select status from tenancy.integration_credential where key_id = ?",
                String.class, keyId)).isEqualTo("ACTIVE");

        mvc.perform(get("/api/v1/tenant/integration-credentials").header("Authorization", admin(tenantB)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void onlyTenantAdminsManageCredentials() throws Exception {
        String auditor = TestTokens.bearer(tenantA.toString(), "AUDITOR");
        mvc.perform(post("/api/v1/tenant/integration-credentials").header("Authorization", auditor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenant/integration-credentials").header("Authorization", auditor))
                .andExpect(status().isForbidden());
    }

    @Test
    void blankDescriptionIs422() throws Exception {
        mvc.perform(post("/api/v1/tenant/integration-credentials").header("Authorization", admin(tenantA))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"\"}"))
                .andExpect(status().isUnprocessableContent());
    }
}

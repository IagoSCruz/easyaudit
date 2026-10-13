package br.com.easyaudit.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.tenancy.application.CredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Usa a fixture {@link TwoTenants}: um tenant não enxerga nem altera recursos do outro. */
@SpringBootTest(classes = TenancyTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class TenantIsolationIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CredentialService credentials;

    String keyOfA;

    @BeforeEach
    void setUp() {
        TenancyFixtures.seedTwoTenants(jdbc);
        keyOfA = credentials.create(TwoTenants.A.value(), "Credencial do A").credential().keyId();
    }

    @Test
    void tenantBGets404WhenTouchingACredentialOfTenantA() throws Exception {
        mvc.perform(post("/api/v1/tenant/integration-credentials/" + keyOfA + "/revoke")
                        .header("Authorization", TwoTenants.bearerB("TENANT_ADMIN")))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select status from tenancy.integration_credential where key_id = ?",
                String.class, keyOfA)).isEqualTo("ACTIVE");
    }

    @Test
    void listingsAreScopedToTheCallersTenant() throws Exception {
        mvc.perform(get("/api/v1/tenant/integration-credentials")
                        .header("Authorization", TwoTenants.bearerB("TENANT_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/tenant/integration-credentials")
                        .header("Authorization", TwoTenants.bearerA("TENANT_ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        mvc.perform(get("/api/v1/tenant/cnpjs").header("Authorization", TwoTenants.bearerB("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].cnpj").value("99888777"));
    }
}

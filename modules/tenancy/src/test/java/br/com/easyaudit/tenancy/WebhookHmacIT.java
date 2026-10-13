package br.com.easyaudit.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.tenancy.application.CredentialService;
import br.com.easyaudit.tenancy.application.TenantService;
import br.com.easyaudit.tenancy.infrastructure.web.HmacAuthenticationFilter;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(classes = TenancyTestApplication.class,
        properties = {"spring.jpa.hibernate.ddl-auto=none", "easyaudit.webhooks.max-body-bytes=1024"})
@AutoConfigureMockMvc
class WebhookHmacIT {

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
    @Autowired
    CredentialService credentials;

    UUID tenantA;
    String keyId;
    String secret;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from tenancy.integration_credential");
        jdbc.update("delete from tenancy.tenant_cnpj");
        jdbc.update("delete from tenancy.cnpj_root");
        jdbc.update("delete from tenancy.tenant");
        tenantA = tenants.create("Loja A", "loja-a", List.of("11222333000181")).id();
        var created = credentials.create(tenantA, "Webhook");
        keyId = created.credential().keyId();
        secret = created.secret();
        WebhookProbeController.CALLS.set(0);
    }

    private MockHttpServletRequestBuilder signed(String body, String key, String sec, long timestamp) {
        String ts = Long.toString(timestamp);
        String signature = "sha256=" + HmacAuthenticationFilter.sign(sec, ts, body.getBytes(StandardCharsets.UTF_8));
        return post("/webhooks/v1/probe").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HmacAuthenticationFilter.KEY_ID_HEADER, key)
                .header(HmacAuthenticationFilter.TIMESTAMP_HEADER, ts)
                .header(HmacAuthenticationFilter.SIGNATURE_HEADER, signature);
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    @Test
    void validSignatureIsAcceptedInTheCredentialsTenant() throws Exception {
        String body = "{\"cte\":\"<xml/>\"}";
        mvc.perform(signed(body, keyId, secret, now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value(tenantA.toString()))
                .andExpect(jsonPath("$.body").value(body));
        assertThat(WebhookProbeController.CALLS.get()).isEqualTo(1);
    }

    @Test
    void invalidSignatureIs401AndNothingIsProcessed() throws Exception {
        mvc.perform(signed("{\"a\":1}", keyId, "outro-segredo", now()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
        // corpo adulterado depois de assinado
        var tampered = signed("{\"a\":1}", keyId, secret, now()).content("{\"a\":2}");
        mvc.perform(tampered).andExpect(status().isUnauthorized());
        assertThat(WebhookProbeController.CALLS.get()).isZero();
    }

    @Test
    void timestampOutsideTheWindowIs401() throws Exception {
        mvc.perform(signed("{}", keyId, secret, now() - 301)).andExpect(status().isUnauthorized());
        mvc.perform(signed("{}", keyId, secret, now() + 301)).andExpect(status().isUnauthorized());
        mvc.perform(signed("{}", keyId, secret, now() - 290)).andExpect(status().isOk());
        assertThat(WebhookProbeController.CALLS.get()).isEqualTo(1);
    }

    @Test
    void revokedOrUnknownCredentialIs401() throws Exception {
        credentials.revoke(tenantA, keyId);
        mvc.perform(signed("{}", keyId, secret, now())).andExpect(status().isUnauthorized());
        mvc.perform(signed("{}", "ea_desconhecida", secret, now())).andExpect(status().isUnauthorized());
        assertThat(WebhookProbeController.CALLS.get()).isZero();
    }

    @Test
    void missingHeadersAre401() throws Exception {
        mvc.perform(post("/webhooks/v1/probe").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/v1/probe").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(HmacAuthenticationFilter.KEY_ID_HEADER, keyId)
                        .header(HmacAuthenticationFilter.TIMESTAMP_HEADER, Long.toString(now()))
                        .header(HmacAuthenticationFilter.SIGNATURE_HEADER, "lixo"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bodyAboveTheLimitIs413() throws Exception {
        String big = "{\"x\":\"" + "a".repeat(2000) + "\"}";
        mvc.perform(signed(big, keyId, secret, now()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(WebhookProbeController.CALLS.get()).isZero();
    }

    @Test
    void jwtDoesNotAuthenticateWebhooks() throws Exception {
        mvc.perform(post("/webhooks/v1/probe").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("Authorization", br.com.easyaudit.platform.testsupport.TestTokens.bearer(tenantA.toString(), "TENANT_ADMIN")))
                .andExpect(status().isUnauthorized());
    }
}

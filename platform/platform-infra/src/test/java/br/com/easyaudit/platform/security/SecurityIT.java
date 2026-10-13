package br.com.easyaudit.platform.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import br.com.easyaudit.platform.testsupport.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ProbeController.class)
@ContextConfiguration(classes = {SecurityTestApplication.class, ProbeController.class})
class SecurityIT {

    private static final String TENANT = "11111111-1111-4111-8111-111111111111";

    @Autowired
    MockMvc mvc;

    private static String token(Map<String, Object> claims) {
        return TestTokens.bearer((String) claims.get("tenant_id"), rolesOf(claims));
    }

    @SuppressWarnings("unchecked")
    private static String[] rolesOf(Map<String, Object> claims) {
        Object ra = claims.get("realm_access");
        return ra == null ? new String[0] : ((List<String>) ((Map<String, Object>) ra).get("roles")).toArray(String[]::new);
    }

    private static Map<String, Object> roles(String... roles) {
        return Map.of("realm_access", Map.of("roles", List.of(roles)));
    }

    @Test
    void withoutTokenIs401Problem() throws Exception {
        mvc.perform(get("/api/v1/probe"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void expiredTokenIs401() throws Exception {
        String expired = "Bearer " + TestTokens.token(TENANT, Instant.now().minusSeconds(120), TestTokens.SECRET);
        mvc.perform(get("/api/v1/probe").header("Authorization", expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void invalidSignatureIs401() throws Exception {
        String forged = "Bearer " + TestTokens.token(TENANT, Instant.now().plusSeconds(600),
                "ffffffffffffffffffffffffffffffff".getBytes());
        mvc.perform(get("/api/v1/probe").header("Authorization", forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenWithoutTenantClaimIs403() throws Exception {
        mvc.perform(get("/api/v1/probe").header("Authorization", token(roles("AUDITOR"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("TENANT_CLAIM_MISSING"));
    }

    @Test
    void tokenWithTenantClaimRunsInsideTenantContext() throws Exception {
        mvc.perform(get("/api/v1/probe").header("Authorization", token(Map.of("tenant_id", TENANT))))
                .andExpect(status().isOk())
                .andExpect(content().string(TENANT));
    }

    @Test
    void adminRequiresPlatformAdminRole() throws Exception {
        mvc.perform(get("/admin/v1/probe").header("Authorization",
                        token(Map.of("tenant_id", TENANT, "realm_access", Map.of("roles", List.of("AUDITOR"))))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // PLATFORM_ADMIN não tem tenant_id e só acessa /admin/**
        mvc.perform(get("/admin/v1/probe").header("Authorization", token(roles("PLATFORM_ADMIN"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/probe").header("Authorization", token(roles("PLATFORM_ADMIN"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void healthIsPublicAndOtherPathsAreDenied() throws Exception {
        // o slice não tem o actuator: 404 prova que a segurança deixou passar (sem 401/403)
        mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
        mvc.perform(get("/qualquer-outra")).andExpect(status().isForbidden());
    }

    @Test
    void validationErrorsAreProblemsWithFieldErrors() throws Exception {
        mvc.perform(post("/api/v1/probe").header("Authorization", token(Map.of("tenant_id", TENANT)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void unexpectedErrorsDoNotLeakDetails() throws Exception {
        mvc.perform(get("/api/v1/boom").header("Authorization", token(Map.of("tenant_id", TENANT))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("segredo"))));
    }
}

package br.com.easyaudit.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import br.com.easyaudit.freight.application.CarrierService;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuditPolicyIT extends AbstractAuditIT {

    static final String ADMIN = TwoTenants.bearerA("TENANT_ADMIN");

    @Autowired
    MockMvc mvc;
    @Autowired
    CarrierService carriers;

    private UUID carrier() throws Exception {
        return TenantContext.callAs(TwoTenants.A, () -> carriers.create("11222333000181", "Rápidos").id());
    }

    private static String policy(String abs, String pct, boolean auto, String weight, String cargo) {
        return "{\"absoluteTolerance\":" + abs + ",\"percentTolerance\":" + pct + ",\"autoApprove\":" + auto
                + ",\"weightTolerancePercent\":" + weight + ",\"cargoValueTolerancePercent\":" + cargo + "}";
    }

    @Test
    void aTenantWithoutConfigurationGetsTheDefaultPolicy() throws Exception {
        mvc.perform(get("/api/v1/audit-policies/default").header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.absoluteTolerance").value(0.0))
                .andExpect(jsonPath("$.percentTolerance").value(0.0))
                .andExpect(jsonPath("$.autoApprove").value(true))
                .andExpect(jsonPath("$.weightTolerancePercent").value(5.0))
                .andExpect(jsonPath("$.cargoValueTolerancePercent").value(1.0))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void theTenantAdminChangesTheDefaultPolicy() throws Exception {
        mvc.perform(put("/api/v1/audit-policies/default").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(policy("1.00", "1", false, "7", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percentTolerance").value(1.0))
                .andExpect(jsonPath("$.autoApprove").value(false));
        mvc.perform(get("/api/v1/audit-policies/default").header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(jsonPath("$.absoluteTolerance").value(1.0)).andExpect(jsonPath("$.weightTolerancePercent").value(7.0));
        // outro tenant continua com o padrão
        mvc.perform(get("/api/v1/audit-policies/default").header("Authorization", TwoTenants.bearerB("VIEWER")))
                .andExpect(jsonPath("$.autoApprove").value(true));
    }

    @Test
    void aCarrierPolicyOverridesOnlyTheFieldsItSets() throws Exception {
        UUID carrier = carrier();
        mvc.perform(put("/api/v1/audit-policies/default").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(policy("0.00", "1", true, "5", "1"))).andExpect(status().isOk());

        mvc.perform(put("/api/v1/carriers/" + carrier + "/audit-policy").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"percentTolerance\":2.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.percentTolerance").value(2.5))
                .andExpect(jsonPath("$.overrides.absoluteTolerance").doesNotExist())
                .andExpect(jsonPath("$.effective.percentTolerance").value(2.5))
                .andExpect(jsonPath("$.effective.autoApprove").value(true))
                .andExpect(jsonPath("$.effective.weightTolerancePercent").value(5.0))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // se a política do tenant muda depois, o campo não sobrescrito acompanha
        mvc.perform(put("/api/v1/audit-policies/default").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(policy("0.00", "1", false, "9", "1"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/carriers/" + carrier + "/audit-policy").header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(jsonPath("$.effective.percentTolerance").value(2.5))
                .andExpect(jsonPath("$.effective.autoApprove").value(false))
                .andExpect(jsonPath("$.effective.weightTolerancePercent").value(9.0));

        mvc.perform(delete("/api/v1/carriers/" + carrier + "/audit-policy").header("Authorization", ADMIN))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/carriers/" + carrier + "/audit-policy").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.effective.percentTolerance").value(1.0))
                .andExpect(jsonPath("$.overrides.percentTolerance").doesNotExist());
    }

    @Test
    void invalidValuesAre422() throws Exception {
        mvc.perform(put("/api/v1/audit-policies/default").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content(policy("-1", "120", true, "101", "1")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[?(@.field=='percentTolerance')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='absoluteTolerance')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='weightTolerancePercent')]").exists())
                .andExpect(OpenApiResponseValidator.conformsToContract());
        UUID carrier = carrier();
        mvc.perform(put("/api/v1/carriers/" + carrier + "/audit-policy").header("Authorization", ADMIN)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"percentTolerance\":120}"))
                .andExpect(status().isUnprocessableContent());
        // nada foi gravado
        mvc.perform(get("/api/v1/audit-policies/default").header("Authorization", ADMIN))
                .andExpect(jsonPath("$.percentTolerance").value(0.0));
    }

    @Test
    void onlyTenantAdminsChangePolicies() throws Exception {
        UUID carrier = carrier();
        String auditor = TwoTenants.bearerA("AUDITOR");
        mvc.perform(put("/api/v1/audit-policies/default").header("Authorization", auditor)
                .contentType(MediaType.APPLICATION_JSON).content(policy("0", "0", true, "5", "1"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/carriers/" + carrier + "/audit-policy").header("Authorization", auditor)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/carriers/" + carrier + "/audit-policy").header("Authorization", auditor))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownOrForeignCarriersAre404() throws Exception {
        UUID carrier = carrier();
        mvc.perform(get("/api/v1/carriers/" + UUID.randomUUID() + "/audit-policy").header("Authorization", ADMIN))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/carriers/" + carrier + "/audit-policy").header("Authorization", TwoTenants.bearerB("TENANT_ADMIN")))
                .andExpect(status().isNotFound());
        assertThat(carrier).isNotNull();
    }
}

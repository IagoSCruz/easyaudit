package br.com.easyaudit.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class AuditActionsApiIT extends AuditFlowSupport {

    static final String AUDITOR = TwoTenants.bearerA("AUDITOR");

    @Autowired
    MockMvc mvc;

    private int sequence = 3000;

    private StoredCase newCase(String total) throws Exception {
        int n = sequence++;
        var nfe = NfeFixture.builder().number(n).volume(1, "10.000", "12.500").build();
        process(nfe);
        return audit(process(cte().number(n).nfeKey(nfe.accessKey()).totalAmount(total).build())).orElseThrow();
    }

    private ResultActions act(String token, String path, String body) throws Exception {
        return mvc.perform(post("/api/v1/audits/" + path).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void anAuditorApprovesACaseWithAGlossa() throws Exception {
        StoredCase c = newCase("80.00");
        act(AUDITOR, c.id() + "/approve",
                "{\"version\":" + c.version() + ",\"approvedAmount\":66.02,\"justification\":\"Glosa conforme tabela\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAmount").value(66.02))
                .andExpect(jsonPath("$.version").value(c.version() + 1))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        org.assertj.core.api.Assertions.assertThat(admin.queryForObject(
                "select actor from audit.case_transition where case_id = ? and action = 'APPROVE'", String.class, c.id()))
                .isNotBlank().isNotEqualTo("SYSTEM");
    }

    @Test
    void aViewerCannotAct() throws Exception {
        StoredCase c = newCase("66.02");
        act(TwoTenants.bearerA("VIEWER"), c.id() + "/approve", "{\"version\":" + c.version() + "}")
                .andExpect(status().isForbidden());
    }

    @Test
    void aStaleVersionIs409WithTheContractProblem() throws Exception {
        StoredCase c = newCase("66.02");
        act(AUDITOR, c.id() + "/approve", "{\"version\":" + (c.version() - 1) + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentStatus").value("COMPLIANT"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void anInvalidTransitionIs409WithTheCurrentStatus() throws Exception {
        StoredCase c = newCase("66.02");
        act(AUDITOR, c.id() + "/reopen", "{\"version\":" + c.version() + ",\"reason\":\"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
                .andExpect(jsonPath("$.currentStatus").value("COMPLIANT"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void aDivergentCaseWithoutJustificationIs422() throws Exception {
        StoredCase c = newCase("80.00");
        act(AUDITOR, c.id() + "/approve", "{\"version\":" + c.version() + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("justification"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void disputeResolveAndReopenThroughTheApi() throws Exception {
        StoredCase c = newCase("80.00");
        act(AUDITOR, c.id() + "/dispute", "{\"version\":" + c.version()
                + ",\"category\":\"VALUE_ABOVE_REFERENCE\",\"description\":\"Acima da tabela\",\"amountDue\":66.02}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISPUTED"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        long v = c.version() + 1;
        act(AUDITOR, c.id() + "/resolve", "{\"version\":" + v + ",\"agreedAmount\":70.00,\"note\":\"Acordo\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAmount").value(70.0));
        act(AUDITOR, c.id() + "/reopen", "{\"version\":" + (v + 1) + ",\"reason\":\"Rever\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DIVERGENT"))
                .andExpect(jsonPath("$.approvedAmount").doesNotExist());
    }

    @Test
    void withdrawThroughTheApi() throws Exception {
        StoredCase c = newCase("80.00");
        act(AUDITOR, c.id() + "/dispute", "{\"version\":" + c.version()
                + ",\"category\":\"OTHER\",\"description\":\"x\",\"amountDue\":0}").andExpect(status().isOk());
        act(AUDITOR, c.id() + "/withdraw", "{\"version\":" + (c.version() + 1) + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DIVERGENT"));
    }

    @Test
    void batchApprovalReturnsOneResultPerCase() throws Exception {
        StoredCase ok = newCase("66.02");
        StoredCase divergent = newCase("80.00");
        act(AUDITOR, "approve-batch", "{\"items\":[{\"caseId\":\"" + ok.id() + "\",\"version\":" + ok.version()
                + "},{\"caseId\":\"" + divergent.id() + "\",\"version\":" + divergent.version() + "},{\"caseId\":\""
                + UUID.randomUUID() + "\",\"version\":1}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeeded").value(1))
                .andExpect(jsonPath("$.failed").value(2))
                .andExpect(jsonPath("$.items[0].success").value(true))
                .andExpect(jsonPath("$.items[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.items[1].errorCode").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.items[2].errorCode").value("NOT_FOUND"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void aBatchOver500Is422() throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            items.append(i == 0 ? "" : ",").append("{\"caseId\":\"").append(UUID.randomUUID()).append("\",\"version\":1}");
        }
        act(AUDITOR, "approve-batch", "{\"items\":[" + items + "]}").andExpect(status().isUnprocessableContent());
    }
}

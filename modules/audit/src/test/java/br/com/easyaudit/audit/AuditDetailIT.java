package br.com.easyaudit.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import br.com.easyaudit.audit.application.AuditCases;
import br.com.easyaudit.audit.application.CaseWorkflow;
import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuditDetailIT extends AuditFlowSupport {

    static final String VIEWER = TwoTenants.bearerA("VIEWER");

    @Autowired
    MockMvc mvc;
    @Autowired
    CaseWorkflow workflow;

    @Test
    void aDisputedCaseShowsTheDisputeTheRevisionThatMotivatedItAndTheHistory() throws Exception {
        var nfe = NfeFixture.builder().number(61).volume(1, "10.000", "12.500").build();
        process(nfe);
        StoredCase c = audit(process(cte().number(61).nfeKey(nfe.accessKey()).totalAmount("80.00").build())).orElseThrow();
        StoredCase disputed = as(() -> workflow.dispute(c.id(), c.version(), CaseWorkflow.DisputeCategory.VALUE_ABOVE_REFERENCE,
                "Acima da tabela", new BigDecimal("66.02"), "ana"));

        mvc.perform(get("/api/v1/audits/" + c.id()).header("Authorization", VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.status").value("DISPUTED"))
                .andExpect(jsonPath("$.case.version").value(disputed.version()))
                .andExpect(jsonPath("$.cte.accessKey").value(c.cteAccessKey()))
                .andExpect(jsonPath("$.linkedNfes.length()").value(1))
                .andExpect(jsonPath("$.pendingNfeKeys.length()").value(0))
                .andExpect(jsonPath("$.currentRevision.revision").value(1))
                .andExpect(jsonPath("$.currentRevision.classification").value("DIVERGENT"))
                .andExpect(jsonPath("$.currentRevision.snapshot.outcome.checks").isArray())
                .andExpect(jsonPath("$.currentRevision.snapshot.outcome.componentComparison").isArray())
                .andExpect(jsonPath("$.currentRevision.snapshot.input.policy").exists())
                .andExpect(jsonPath("$.previousRevisions.length()").value(0))
                .andExpect(jsonPath("$.history.length()").value(2))
                .andExpect(jsonPath("$.history[0].fromStatus").value("PENDING_AUDIT"))
                .andExpect(jsonPath("$.history[0].toStatus").value("DIVERGENT"))
                .andExpect(jsonPath("$.history[0].actor").value("SYSTEM"))
                .andExpect(jsonPath("$.history[1].toStatus").value("DISPUTED"))
                .andExpect(jsonPath("$.history[1].actor").value("ana"))
                .andExpect(jsonPath("$.dispute.category").value("VALUE_ABOVE_REFERENCE"))
                .andExpect(jsonPath("$.dispute.description").value("Acima da tabela"))
                .andExpect(jsonPath("$.dispute.amountDue").value(66.02))
                .andExpect(jsonPath("$.dispute.status").value("OPEN"))
                .andExpect(jsonPath("$.dispute.revision").value(1))
                .andExpect(jsonPath("$.dispute.createdBy").value("ana"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void reauditedCasesListPreviousRevisionsAndPendingNfes() throws Exception {
        var present = NfeFixture.builder().number(62).volume(1, "10.000", "12.500").build();
        var missing = NfeFixture.builder().number(63).volume(1, "10.000", "12.500").build();
        process(present);
        StoredCase c = audit(process(cte().number(62).nfeKey(present.accessKey()).nfeKey(missing.accessKey()).build())).orElseThrow();
        as(() -> auditCases.reaudit(c.id(), AuditCases.Trigger.MANUAL, true));

        mvc.perform(get("/api/v1/audits/" + c.id()).header("Authorization", VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.case.currentRevision").value(2))
                .andExpect(jsonPath("$.currentRevision.revision").value(2))
                .andExpect(jsonPath("$.currentRevision.trigger").value("MANUAL"))
                .andExpect(jsonPath("$.previousRevisions.length()").value(1))
                .andExpect(jsonPath("$.previousRevisions[0].revision").value(1))
                .andExpect(jsonPath("$.previousRevisions[0].trigger").value("INITIAL"))
                .andExpect(jsonPath("$.linkedNfes.length()").value(1))
                .andExpect(jsonPath("$.pendingNfeKeys[0]").value(missing.accessKey()))
                .andExpect(jsonPath("$.dispute").doesNotExist())
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void anUnknownCaseIs404() throws Exception {
        mvc.perform(get("/api/v1/audits/" + UUID.randomUUID()).header("Authorization", VIEWER))
                .andExpect(status().isNotFound()).andExpect(OpenApiResponseValidator.conformsToContract());
    }
}

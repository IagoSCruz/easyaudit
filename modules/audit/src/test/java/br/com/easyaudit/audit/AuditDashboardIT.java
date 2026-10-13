package br.com.easyaudit.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import br.com.easyaudit.audit.fixtures.AuditCaseFixtures;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class AuditDashboardIT extends AbstractAuditIT {

    static final String VIEWER = TwoTenants.bearerA("VIEWER");

    @Autowired
    MockMvc mvc;

    private AuditCaseFixtures.Builder aCase() {
        return AuditCaseFixtures.aCase(admin, TwoTenants.A.value());
    }

    private ResultActions summary(String query) throws Exception {
        return mvc.perform(get("/api/v1/dashboard/summary" + query).header("Authorization", VIEWER));
    }

    @Test
    void approvedAmountAndSavingsFollowTheSpecScenario() throws Exception {
        aCase().status("APPROVED").charged("103.00").approved("100.00").insert();
        aCase().status("APPROVED").charged("66.02").approved("66.02").insert();
        aCase().status("PAID").charged("50.00").approved("45.00").insert();
        // casos que não entram na economia
        aCase().status("DIVERGENT").classification("DIVERGENT").charged("40.00").reference("30.00").divergence("10.00").insert();
        aCase().status("CANCELLED").charged("999.00").insert();

        summary("?from=2026-10-01&to=2026-10-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalApproved").value(211.02))
                .andExpect(jsonPath("$.savings").value(8.0))
                .andExpect(jsonPath("$.totalCharged").value(259.02)) // sem o cancelado
                .andExpect(jsonPath("$.totalDivergence").value(10.0))
                .andExpect(jsonPath("$.caseCount").value(4))
                .andExpect(jsonPath("$.byStatus.length()").value(9))
                .andExpect(jsonPath("$.byStatus[?(@.status=='APPROVED')].count").value(2))
                .andExpect(jsonPath("$.byStatus[?(@.status=='APPROVED')].chargedAmount").value(169.02))
                .andExpect(jsonPath("$.byStatus[?(@.status=='PAID')].count").value(1))
                .andExpect(jsonPath("$.byStatus[?(@.status=='CANCELLED')].count").value(1))
                .andExpect(jsonPath("$.byStatus[?(@.status=='DISPUTED')].count").value(0))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void theConformanceRateIsCalculatedOverClassifiedCases() throws Exception {
        for (int i = 0; i < 8; i++) {
            aCase().status("COMPLIANT").classification("COMPLIANT").insert();
        }
        for (int i = 0; i < 2; i++) {
            aCase().status("DIVERGENT").classification("DIVERGENT").divergence("5.00").insert();
        }
        for (int i = 0; i < 5; i++) {
            aCase().status("NOT_AUDITABLE").classification("NOT_AUDITABLE").insert();
        }
        summary("").andExpect(jsonPath("$.conformanceRate").value(80.0)).andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void theRateUsesTheCurrentClassificationEvenAfterTheCaseWasApproved() throws Exception {
        aCase().status("APPROVED").classification("DIVERGENT").charged("10.00").approved("9.00").insert();
        aCase().status("APPROVED").classification("COMPLIANT").insert();
        summary("").andExpect(jsonPath("$.conformanceRate").value(50.0));
    }

    @Test
    void aPeriodWithoutDataHasZerosAndANullRate() throws Exception {
        aCase().status("APPROVED").approved("10.00").insert(); // fora do período
        summary("?from=2025-01-01&to=2025-01-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(0))
                .andExpect(jsonPath("$.totalCharged").value(0))
                .andExpect(jsonPath("$.totalReference").value(0))
                .andExpect(jsonPath("$.totalDivergence").value(0))
                .andExpect(jsonPath("$.totalApproved").value(0))
                .andExpect(jsonPath("$.savings").value(0))
                .andExpect(jsonPath("$.conformanceRate").doesNotExist())
                .andExpect(jsonPath("$.byStatus[?(@.count != 0)]").isEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void theSummaryCanBeFilteredByCarrier() throws Exception {
        UUID x = UUID.randomUUID();
        aCase().carrierId(x).status("APPROVED").charged("10.00").approved("10.00").insert();
        aCase().carrierId(UUID.randomUUID()).status("APPROVED").charged("20.00").approved("20.00").insert();
        summary("?carrierId=" + x).andExpect(jsonPath("$.totalApproved").value(10.0)).andExpect(jsonPath("$.caseCount").value(1));
    }

    @Test
    void anInvertedPeriodIs400() throws Exception {
        summary("?from=2026-10-31&to=2026-10-01").andExpect(status().isBadRequest());
    }

    @Test
    void theRankingIsOrderedByDivergenceDescending() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // A: divergência 500 (2 casos), B: 1200, C: sem divergência
        for (String d : new String[] {"200.00", "300.00"}) {
            aCase().carrier("11111111000111", "A Transportes").carrierId(a).status("DIVERGENT").classification("DIVERGENT")
                    .charged("1000.00").divergence(d).insert();
        }
        aCase().carrier("22222222000122", "B Logística").carrierId(b).status("DIVERGENT").classification("DIVERGENT")
                .charged("5000.00").divergence("1200.00").insert();
        aCase().carrier("22222222000122", "B Logística").carrierId(b).status("APPROVED").classification("COMPLIANT")
                .charged("100.00").approved("90.00").insert();
        aCase().carrier("33333333000133", "C Cargas").carrierId(c).status("COMPLIANT").classification("COMPLIANT").insert();
        aCase().carrier("33333333000133", "C Cargas").carrierId(c).status("CANCELLED").classification("DIVERGENT")
                .divergence("9999.00").insert(); // cancelado não conta

        mvc.perform(get("/api/v1/dashboard/carriers").param("from", "2026-10-01").param("to", "2026-10-31")
                        .header("Authorization", VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].carrierName").value("B Logística"))
                .andExpect(jsonPath("$.items[0].divergenceTotal").value(1200.0))
                .andExpect(jsonPath("$.items[0].divergentCount").value(1))
                .andExpect(jsonPath("$.items[0].caseCount").value(2))
                .andExpect(jsonPath("$.items[0].divergenceRate").value(50.0))
                .andExpect(jsonPath("$.items[0].savings").value(10.0))
                .andExpect(jsonPath("$.items[0].carrierId").value(b.toString()))
                .andExpect(jsonPath("$.items[1].carrierName").value("A Transportes"))
                .andExpect(jsonPath("$.items[1].divergenceTotal").value(500.0))
                .andExpect(jsonPath("$.items[1].divergenceRate").value(100.0))
                .andExpect(jsonPath("$.items[2].carrierName").value("C Cargas"))
                .andExpect(jsonPath("$.items[2].divergenceTotal").value(0))
                .andExpect(jsonPath("$.items[2].divergenceRate").value(0.0))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void theRankingOfAnEmptyPeriodIsEmpty() throws Exception {
        aCase().emission(LocalDate.of(2026, 10, 10)).insert();
        mvc.perform(get("/api/v1/dashboard/carriers").param("from", "2025-01-01").param("to", "2025-01-31")
                        .header("Authorization", VIEWER))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }
}

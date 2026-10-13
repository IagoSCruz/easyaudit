package br.com.easyaudit.audit;

import static org.assertj.core.api.Assertions.assertThat;
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
class AuditQueriesIT extends AbstractAuditIT {

    static final String VIEWER = TwoTenants.bearerA("VIEWER");

    @Autowired
    MockMvc mvc;

    private final UUID carrierX = UUID.randomUUID();
    private final UUID carrierY = UUID.randomUUID();

    private AuditCaseFixtures.Builder aCase() {
        return AuditCaseFixtures.aCase(admin, TwoTenants.A.value());
    }

    private AuditCaseFixtures.Builder x() {
        return aCase().carrier("11222333000181", "Rápidos").carrierId(carrierX);
    }

    private AuditCaseFixtures.Builder y() {
        return aCase().carrier("99888777000166", "Outra").carrierId(carrierY);
    }

    private ResultActions list(String query) throws Exception {
        return mvc.perform(get("/api/v1/audits" + query).header("Authorization", VIEWER));
    }

    @Test
    void theDivergentCasesOfACarrierInThePeriod() throws Exception {
        UUID hit1 = x().status("DIVERGENT").classification("DIVERGENT").emission(LocalDate.of(2026, 10, 5)).uf("SP")
                .charged("120.00").reference("100.00").divergence("20.00").failedChecks("TOTAL_VS_TABLE").insert();
        UUID hit2 = x().status("DIVERGENT").classification("DIVERGENT").emission(LocalDate.of(2026, 10, 20)).uf("RJ")
                .charged("110.00").reference("100.00").divergence("10.00").insert();
        x().status("DIVERGENT").classification("DIVERGENT").emission(LocalDate.of(2026, 9, 30)).insert(); // fora do período
        x().status("COMPLIANT").emission(LocalDate.of(2026, 10, 6)).insert(); // outro estado
        y().status("DIVERGENT").classification("DIVERGENT").emission(LocalDate.of(2026, 10, 6)).insert(); // outra transportadora

        list("?status=DIVERGENT&carrierId=" + carrierX + "&from=2026-10-01&to=2026-10-31")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(jsonPath("$.page.page").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                // ordenação padrão: emissão decrescente
                .andExpect(jsonPath("$.items[0].id").value(hit2.toString()))
                .andExpect(jsonPath("$.items[1].id").value(hit1.toString()))
                .andExpect(jsonPath("$.items[1].status").value("DIVERGENT"))
                .andExpect(jsonPath("$.items[1].version").value(1))
                .andExpect(jsonPath("$.items[1].carrierName").value("Rápidos"))
                .andExpect(jsonPath("$.items[1].emissionDate").value("2026-10-05"))
                .andExpect(jsonPath("$.items[1].destinationUf").value("SP"))
                .andExpect(jsonPath("$.items[1].chargedAmount").value(120.0))
                .andExpect(jsonPath("$.items[1].referenceAmount").value(100.0))
                .andExpect(jsonPath("$.items[1].divergenceAmount").value(20.0))
                .andExpect(jsonPath("$.items[1].failedChecks[0]").value("TOTAL_VS_TABLE"))
                .andExpect(jsonPath("$.items[1].cteAccessKey").isString())
                .andExpect(jsonPath("$.items[1].cteNumber").isString())
                .andExpect(jsonPath("$.items[1].cteSeries").value("001"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void aPageOf500ItemsIs400IndicatingTheMaximum() throws Exception {
        list("?size=500")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAGE_SIZE_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("100")))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        list("?size=100").andExpect(status().isOk());
        list("?size=0").andExpect(status().isBadRequest());
        list("?page=-1").andExpect(status().isBadRequest());
    }

    @Test
    void sortingAndPaging() throws Exception {
        UUID low = x().charged("10.00").divergence("1.00").emission(LocalDate.of(2026, 10, 1)).insert();
        UUID mid = x().charged("50.00").divergence("30.00").emission(LocalDate.of(2026, 10, 2)).insert();
        UUID high = x().charged("90.00").divergence("5.00").emission(LocalDate.of(2026, 10, 3)).insert();

        list("?sort=chargedAmount&direction=asc").andExpect(jsonPath("$.items[0].id").value(low.toString()))
                .andExpect(jsonPath("$.items[2].id").value(high.toString()));
        list("?sort=divergenceAmount&direction=desc").andExpect(jsonPath("$.items[0].id").value(mid.toString()));
        list("?sort=emissionDate").andExpect(jsonPath("$.items[0].id").value(high.toString()));
        list("?sort=emissionDate&direction=asc&size=2&page=1")
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(high.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));
        list("?sort=nada").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SORT"));
    }

    @Test
    void theOtherFilters() throws Exception {
        UUID sp = x().status("DIVERGENT").classification("DIVERGENT").uf("SP").divergence("20.00")
                .failedChecks("TOTAL_VS_TABLE", "WEIGHT").alerts("QUOTE_ABOVE").insert();
        UUID rj = x().status("COMPLIANT").uf("RJ").divergence("0.00").insert();
        String key = "35261011222333000181570010000007771123456780";
        UUID byKey = x().status("APPROVED").uf("MG").accessKey(key).insert();

        list("?destinationUf=sp").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(sp.toString()));
        list("?failedCheck=WEIGHT").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(sp.toString()));
        list("?alert=QUOTE_ABOVE").andExpect(jsonPath("$.items.length()").value(1));
        list("?minDivergence=10").andExpect(jsonPath("$.items.length()").value(1));
        list("?status=COMPLIANT&status=APPROVED").andExpect(jsonPath("$.items.length()").value(2));
        list("?q=" + key).andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(byKey.toString()));
        list("?q=777").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(byKey.toString()));
        list("?q=000000777").andExpect(jsonPath("$.items.length()").value(1));
        list("?q=abc").andExpect(jsonPath("$.items.length()").value(0));
        list("?status=NADA").andExpect(status().isBadRequest());
        list("?from=2026-10-31&to=2026-10-01").andExpect(status().isBadRequest());
        assertThat(rj).isNotNull();
    }

    @Test
    void casesOfOtherTenantsAreInvisible() throws Exception {
        UUID mine = x().insert();
        UUID theirs = AuditCaseFixtures.aCase(admin, TwoTenants.B.value()).insert();

        list("").andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(mine.toString()));
        mvc.perform(get("/api/v1/audits").header("Authorization", TwoTenants.bearerB("VIEWER")))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].id").value(theirs.toString()));
        mvc.perform(get("/api/v1/audits/" + theirs).header("Authorization", VIEWER)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", TwoTenants.bearerB("VIEWER")))
                .andExpect(jsonPath("$.caseCount").value(1));
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/audits")).andExpect(status().isUnauthorized());
    }
}

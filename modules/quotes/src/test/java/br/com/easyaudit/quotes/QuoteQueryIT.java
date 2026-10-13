package br.com.easyaudit.quotes;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class QuoteQueryIT extends AbstractQuotesIT {

    static final String ADMIN = TwoTenants.bearerA("TENANT_ADMIN");
    static final String VIEWER = TwoTenants.bearerA("VIEWER");

    @Autowired
    MockMvc mvc;

    private String register(String source, String externalId, String carrier, String amount, String extra) throws Exception {
        String body = "{\"source\":\"" + source + "\",\"externalId\":\"" + externalId + "\",\"carrierCnpj\":\"" + carrier
                + "\",\"amount\":" + amount + ",\"quotedAt\":\"2026-10-09T10:00:00-03:00\",\"destinationCep\":\"20040020\"" + extra + "}";
        return JsonPath.read(mvc.perform(post("/api/v1/quotes").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString(), "$.id");
    }

    @Test
    void theUnlinkedFilterReturnsOnlyQuotesWithoutAnNfe() throws Exception {
        var nfe = NfeFixture.builder().number(61).build();
        processNfe(TwoTenants.A, nfe);
        register("VTEX", "linked", "11222333000181", "10", ",\"nfeKey\":\"" + nfe.accessKey() + "\"");
        String unlinked = register("VTEX", "waiting", "11222333000181", "11", ",\"orderReference\":\"PED-X\"");

        mvc.perform(get("/api/v1/quotes").param("linkStatus", "UNLINKED").header("Authorization", VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(unlinked))
                .andExpect(jsonPath("$.items[0].linkStatus").value("UNLINKED"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        mvc.perform(get("/api/v1/quotes").param("linkStatus", "LINKED").header("Authorization", VIEWER))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].externalId").value("linked"));
    }

    @Test
    void filtersBySourceCarrierOrderKeyAndPeriod() throws Exception {
        var nfe = NfeFixture.builder().number(62).build();
        register("VTEX", "a", "11222333000181", "10", ",\"orderReference\":\"PED-1\"");
        register("INTELIPOST", "b", "45723174000110", "11", ",\"orderReference\":\"PED-2\"");
        register("INTELIPOST", "c", "11222333000181", "12", ",\"nfeKey\":\"" + nfe.accessKey() + "\"");

        mvc.perform(get("/api/v1/quotes").param("source", "INTELIPOST").header("Authorization", VIEWER))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/v1/quotes").param("carrierCnpj", "11.222.333/0001-81").header("Authorization", VIEWER))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/v1/quotes").param("orderReference", "PED-2").header("Authorization", VIEWER))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].externalId").value("b"));
        mvc.perform(get("/api/v1/quotes").param("nfeKey", nfe.accessKey()).header("Authorization", VIEWER))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.items[0].externalId").value("c"));
        mvc.perform(get("/api/v1/quotes").param("from", "2999-01-01T00:00:00Z").header("Authorization", VIEWER))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/quotes").param("size", "2").param("page", "1").header("Authorization", VIEWER))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.page.totalPages").value(2));
    }

    @Test
    void detailShowsAllRevisions() throws Exception {
        String id = register("INTELIPOST", "Q-981", "11222333000181", "39.90", ",\"orderReference\":\"PED-5521\"");
        register("INTELIPOST", "Q-981", "11222333000181", "42.50", ",\"orderReference\":\"PED-5521\"");

        mvc.perform(get("/api/v1/quotes/" + id).header("Authorization", VIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentRevision").value(2))
                .andExpect(jsonPath("$.amount").value(42.5))
                .andExpect(jsonPath("$.revisions.length()").value(2))
                .andExpect(jsonPath("$.revisions[0].revision").value(1))
                .andExpect(jsonPath("$.revisions[0].amount").value(39.9))
                .andExpect(jsonPath("$.revisions[1].amount").value(42.5))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void otherTenantsSeeNothing() throws Exception {
        String id = register("VTEX", "a", "11222333000181", "10", ",\"orderReference\":\"PED-1\"");
        String viewerB = TwoTenants.bearerB("VIEWER");
        mvc.perform(get("/api/v1/quotes/" + id).header("Authorization", viewerB)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/quotes").header("Authorization", viewerB)).andExpect(jsonPath("$.items.length()").value(0));
    }
}

package br.com.easyaudit.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
class PaymentBatchApiIT extends AbstractSettlementIT {

    static final String FINANCE = TwoTenants.bearerA("FINANCE");

    @Autowired
    MockMvc mvc;

    private ResultActions createBatch(String token, UUID... ids) throws Exception {
        StringBuilder list = new StringBuilder();
        for (UUID id : ids) {
            list.append(list.length() == 0 ? "" : ",").append('"').append(id).append('"');
        }
        return mvc.perform(post("/api/v1/payment-batches").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueDate\":\"2026-11-10\",\"description\":\"Novembro\",\"caseIds\":[" + list + "]}"));
    }

    private String batchId(ResultActions created) throws Exception {
        String body = created.andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.batch.id");
    }

    @Test
    void financeCreatesABatchAndTheResponseConformsToTheContract() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "103.00", "100.00");
        UUID b = approved(CARRIER_X, "Rápidos", "66.02", "66.02");

        createBatch(FINANCE, a, b)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch.status").value("OPEN"))
                .andExpect(jsonPath("$.batch.caseCount").value(2))
                .andExpect(jsonPath("$.batch.chargedTotal").value(169.02))
                .andExpect(jsonPath("$.batch.approvedTotal").value(166.02))
                .andExpect(jsonPath("$.batch.savingsTotal").value(3.0))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        assertThat(statusOf(a)).isEqualTo("SCHEDULED_FOR_PAYMENT");
    }

    @Test
    void onlyFinanceCanChangeBatches() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        createBatch(TwoTenants.bearerA("AUDITOR"), a).andExpect(status().isForbidden());
        createBatch(TwoTenants.bearerA("VIEWER"), a).andExpect(status().isForbidden());
        String id = batchId(createBatch(FINANCE, a));
        mvc.perform(post("/api/v1/payment-batches/" + id + "/cancel").header("Authorization", TwoTenants.bearerA("AUDITOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/payment-batches/" + id + "/export").header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isForbidden());
        // a consulta é aberta a qualquer usuário autenticado do tenant
        mvc.perform(get("/api/v1/payment-batches/" + id).header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk()).andExpect(OpenApiResponseValidator.conformsToContract());
        mvc.perform(get("/api/v1/payment-batches/" + id).header("Authorization", TwoTenants.bearerB("FINANCE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anInvalidCaseIs422ListingTheInvalidOnes() throws Exception {
        UUID ok = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        UUID other = approved(CARRIER_Y, "Outra", "10.00", "10.00");
        createBatch(FINANCE, ok, other)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_BATCH"))
                .andExpect(jsonPath("$.errors[0].field").value("caseIds[" + other + "]"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        assertThat(statusOf(ok)).isEqualTo("APPROVED");
    }

    @Test
    void theCsvIsUtf8WithBomSemicolonAndCommaDecimalsByteForByte() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "103.00", "100.00");
        UUID b = approved(CARRIER_X, "Rápidos", "66.02", "66.02");
        admin.update("update audit.audit_case set cte_access_key = '35261011222333000181570010000001231123456780' where id = ?", a);
        admin.update("update audit.audit_case set cte_access_key = '35261011222333000181570010000004561123456780' where id = ?", b);
        String id = batchId(createBatch(FINANCE, a, b));
        // a chave é copiada para o lote na criação; ajusta a cópia para ter chaves previsíveis
        admin.update("update settlement.batch_item set cte_access_key = '35261011222333000181570010000001231123456780' where case_id = ?", a);
        admin.update("update settlement.batch_item set cte_access_key = '35261011222333000181570010000004561123456780' where case_id = ?", b);

        MvcResult result = mvc.perform(get("/api/v1/payment-batches/" + id + "/export").header("Authorization", FINANCE))
                .andExpect(status().isOk())
                .andReturn();

        String expected = "lote;cnpj_transportadora;transportadora;chave_cte;numero_cte;serie_cte;data_emissao;"
                + "valor_cobrado;valor_aprovado;glosa;vencimento\r\n"
                + id + ";11222333000181;Rápidos;35261011222333000181570010000001231123456780;000000123;001;10/10/2026;"
                + "103,00;100,00;3,00;10/11/2026\r\n"
                + id + ";11222333000181;Rápidos;35261011222333000181570010000004561123456780;000000456;001;10/10/2026;"
                + "66,02;66,02;0,00;10/11/2026\r\n";
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = expected.getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(all);
        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        assertThat(result.getResponse().getHeader("Content-Disposition")).contains("attachment").contains("lote-" + id + ".csv");

        // a exportação ficou registrada com o usuário
        assertThat(admin.queryForObject("select count(*) from settlement.batch_export where batch_id = ?::uuid and row_count = 2"
                + " and exported_by <> ''", Integer.class, id)).isEqualTo(1);
        mvc.perform(get("/api/v1/payment-batches/" + id).header("Authorization", FINANCE))
                .andExpect(jsonPath("$.exports.length()").value(1))
                .andExpect(jsonPath("$.exports[0].rowCount").value(2))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void csvFieldsWithSeparatorsAreQuotedAndFormulasAreNeutralized() throws Exception {
        UUID a = approved(CARRIER_X, "=SOMA(A1;B1)", "10.00", "10.00");
        UUID b = approved(CARRIER_X, "Rápidos; \"Norte\"", "10.00", "10.00");
        admin.update("update audit.audit_case set emission_date = date '2026-10-11' where id = ?", b);
        String id = batchId(createBatch(FINANCE, a, b));

        String csv = new String(mvc.perform(get("/api/v1/payment-batches/" + id + "/export").header("Authorization", FINANCE))
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        assertThat(csv).contains(";\"'=SOMA(A1;B1)\";").doesNotContain(";=SOMA").doesNotContain("\"=SOMA");
        assertThat(csv).contains("\"Rápidos; \"\"Norte\"\"\"");
    }

    @Test
    void paymentAndImmutabilityThroughTheApi() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        String id = batchId(createBatch(FINANCE, a));

        mvc.perform(post("/api/v1/payment-batches/" + id + "/payment").header("Authorization", FINANCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidOn\":\"2999-01-01\",\"reference\":\"PIX\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("paidOn"))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        mvc.perform(post("/api/v1/payment-batches/" + id + "/payment").header("Authorization", FINANCE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidOn\":\"2026-10-01\",\"reference\":\"PIX-E2E-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.status").value("PAID"))
                .andExpect(jsonPath("$.batch.paidOn").value("2026-10-01"))
                .andExpect(jsonPath("$.batch.paymentReference").value("PIX-E2E-123"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        assertThat(statusOf(a)).isEqualTo("PAID");

        mvc.perform(post("/api/v1/payment-batches/" + id + "/cancel").header("Authorization", FINANCE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BATCH_NOT_OPEN"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        mvc.perform(delete("/api/v1/payment-batches/" + id + "/cases/" + a).header("Authorization", FINANCE))
                .andExpect(status().isConflict());
    }

    @Test
    void addRemoveAndCancelThroughTheApi() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        UUID b = approved(CARRIER_X, "Rápidos", "20.00", "20.00");
        String id = batchId(createBatch(FINANCE, a));

        mvc.perform(post("/api/v1/payment-batches/" + id + "/cases").header("Authorization", FINANCE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"caseIds\":[\"" + b + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.batch.caseCount").value(2))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        mvc.perform(delete("/api/v1/payment-batches/" + id + "/cases/" + a).header("Authorization", FINANCE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.batch.caseCount").value(1))
                .andExpect(jsonPath("$.batch.chargedTotal").value(20.0))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        mvc.perform(post("/api/v1/payment-batches/" + id + "/cancel").header("Authorization", FINANCE))
                .andExpect(status().isOk()).andExpect(jsonPath("$.batch.status").value("CANCELLED"))
                .andExpect(OpenApiResponseValidator.conformsToContract());
        assertThat(statusOf(b)).isEqualTo("APPROVED");
    }

    @Test
    void listingFiltersOpenBatches() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        UUID b = approved(CARRIER_X, "Rápidos", "20.00", "20.00");
        createBatch(FINANCE, a).andExpect(status().isCreated());
        String second = batchId(createBatch(FINANCE, b));
        mvc.perform(post("/api/v1/payment-batches/" + second + "/cancel").header("Authorization", FINANCE));

        mvc.perform(get("/api/v1/payment-batches").param("status", "OPEN").header("Authorization", TwoTenants.bearerA("VIEWER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }
}

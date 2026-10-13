package br.com.easyaudit.platform.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import br.com.easyaudit.contracts.api.PingApi;
import br.com.easyaudit.contracts.api.model.PageMeta;
import br.com.easyaudit.contracts.api.model.PingResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;

class ContractValidatorsTest {

    private static final EventContractValidator EVENTS = new EventContractValidator();

    private static final String VALID_PAYLOAD = """
            {"caseId":"6f1c1f0e-9a4a-4a3e-8d3a-0e8a7c1d2b3c","revisionId":"0b3f6a52-3c1e-4c6b-9d52-1f7f1a9d1e11",
             "classification":"DIVERGENT","chargedAmount":152.37,"referenceAmount":140.10,"failedChecks":["TOTAL_VS_TABLE"]}""";

    @Test
    void validEventPayloadPasses() {
        EVENTS.assertValidPayload("AuditCompletedData", VALID_PAYLOAD);
    }

    @Test
    void invalidEventPayloadFails() {
        String invalid = VALID_PAYLOAD.replace("DIVERGENT", "UNKNOWN").replace(",\"failedChecks\":[\"TOTAL_VS_TABLE\"]", "");
        assertThat(EVENTS.validatePayload("AuditCompletedData", invalid)).isNotEmpty();
        assertThatThrownBy(() -> EVENTS.assertValidPayload("AuditCompletedData", invalid))
                .isInstanceOf(AssertionError.class);
    }

    @RestController
    static class PingController implements PingApi {
        private final PingResponse body;

        PingController(PingResponse body) {
            this.body = body;
        }

        @Override
        public ResponseEntity<PingResponse> ping() {
            return ResponseEntity.ok(body);
        }
    }

    @Test
    void conformingResponsePasses() throws Exception {
        PingResponse ok = new PingResponse("UP", new BigDecimal("10.50"), "11222333000181");
        ok.setPage(new PageMeta(0, 20, 1L, 1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PingController(ok)).build();
        mvc.perform(get("/api/v1/ping")).andExpect(status().isOk()).andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void nonConformingResponseFails() {
        PingResponse bad = new PingResponse("UP", new BigDecimal("10.50"), "123");
        bad.setPage(new PageMeta(0, 20, 1L, 1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PingController(bad)).build();
        assertThatThrownBy(() -> mvc.perform(get("/api/v1/ping")).andExpect(OpenApiResponseValidator.conformsToContract()))
                .hasMessageContaining("validation.response.body.schema.minLength");
    }
}

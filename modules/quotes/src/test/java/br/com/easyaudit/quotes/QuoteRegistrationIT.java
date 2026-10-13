package br.com.easyaudit.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import br.com.easyaudit.documents.fixtures.Cnpjs;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.tenancy.application.CredentialService;
import br.com.easyaudit.tenancy.infrastructure.web.HmacAuthenticationFilter;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class QuoteRegistrationIT extends AbstractQuotesIT {

    static final String ADMIN = TwoTenants.bearerA("TENANT_ADMIN");
    static final String CARRIER = "11222333000181";

    @Autowired
    MockMvc mvc;
    @Autowired
    CredentialService credentials;

    static String quote(String source, String externalId, String amount, String extra) {
        return "{\"source\":\"" + source + "\",\"externalId\":\"" + externalId + "\",\"carrierCnpj\":\"" + CARRIER
                + "\",\"service\":\"Rodoviário\",\"amount\":" + amount + ",\"quotedAt\":\"2026-10-09T10:00:00-03:00\","
                + "\"destinationCep\":\"20040020\",\"weightKg\":12.5,\"cargoValue\":1000,\"deliveryDays\":5" + extra + "}";
    }

    private String register(String body) throws Exception {
        return mvc.perform(post("/api/v1/quotes").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void validQuoteIsCreatedByRest() throws Exception {
        mvc.perform(post("/api/v1/quotes").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(quote("INTELIPOST", "Q-981", "39.90", ",\"orderReference\":\"PED-5521\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.result").value("CREATED"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract());
        assertThat(admin.queryForObject("select count(*) from quotes.quote", Integer.class)).isEqualTo(1);
    }

    @Test
    void webhookRegistersInTheCredentialsTenant() throws Exception {
        var credential = credentials.create(TwoTenants.A.value(), "Intelipost");
        String body = quote("INTELIPOST", "Q-981", "39.90", ",\"orderReference\":\"PED-5521\"");
        String ts = Long.toString(Instant.now().getEpochSecond());
        String signature = "sha256=" + HmacAuthenticationFilter.sign(credential.secret(), ts, body.getBytes(StandardCharsets.UTF_8));

        mvc.perform(post("/webhooks/v1/quotes").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HmacAuthenticationFilter.KEY_ID_HEADER, credential.credential().keyId())
                        .header(HmacAuthenticationFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacAuthenticationFilter.SIGNATURE_HEADER, signature))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty());
        assertThat(admin.queryForObject("select tenant_id::text from quotes.quote", String.class)).isEqualTo(TwoTenants.A.toString());

        // sem assinatura válida: 401 e nada registrado
        mvc.perform(post("/webhooks/v1/quotes").contentType(MediaType.APPLICATION_JSON)
                        .content(quote("INTELIPOST", "Q-2", "10", ",\"orderReference\":\"X\"")))
                .andExpect(status().isUnauthorized());
        assertThat(admin.queryForObject("select count(*) from quotes.quote", Integer.class)).isEqualTo(1);
    }

    @Test
    void withoutNfeKeyAndWithoutOrderReferenceIs422() throws Exception {
        mvc.perform(post("/api/v1/quotes").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(quote("VTEX", "Q-1", "10", "")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("nfeKey"))
                .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("ao menos um dos dois")))
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    void invalidNfeKeysAre422() throws Exception {
        String validCteKey = br.com.easyaudit.documents.fixtures.CteFixture.builder().build().accessKey(); // modelo 57
        String validNfeKey = NfeFixture.builder().build().accessKey();
        String badDigit = validNfeKey.substring(0, 43) + ((validNfeKey.charAt(43) - '0' + 1) % 10);
        int n = 0;
        for (String bad : new String[] {"1234", badDigit, validCteKey}) {
            mvc.perform(post("/api/v1/quotes").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                            .content(quote("VTEX", "Q-bad-" + (n++), "10", ",\"nfeKey\":\"" + bad + "\"")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("nfeKey"));
        }
        mvc.perform(post("/api/v1/quotes").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(quote("VTEX", "Q-ok", "10", ",\"nfeKey\":\"" + validNfeKey + "\"")))
                .andExpect(status().isCreated());
    }

    @Test
    void allInvalidFieldsAreReportedTogether() throws Exception {
        String body = "{\"source\":\"VTEX\",\"externalId\":\" \",\"carrierCnpj\":\"11222333000182\",\"amount\":-1,"
                + "\"quotedAt\":\"2026-10-09T10:00:00Z\",\"destinationCep\":\"123\",\"orderReference\":\"P\"}";
        mvc.perform(post("/api/v1/quotes").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='externalId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='carrierCnpj')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='amount')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='destinationCep')]").exists());
    }

    @Test
    void identicalResendChangesNothing() throws Exception {
        String body = quote("INTELIPOST", "Q-981", "39.90", ",\"orderReference\":\"PED-5521\"");
        String first = register(body);
        String id = JsonPath.read(first, "$.id");

        mvc.perform(post("/api/v1/quotes").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("UNCHANGED"))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.revision").value(1));
        assertThat(admin.queryForObject("select count(*) from quotes.quote_revision", Integer.class)).isEqualTo(1);
        assertThat(events("quote.revised")).isEmpty();
    }

    @Test
    void resendWithAChangedAmountCreatesRevision2KeepingRevision1() throws Exception {
        String id = JsonPath.read(register(quote("INTELIPOST", "Q-981", "39.90", ",\"orderReference\":\"PED-5521\"")), "$.id");

        mvc.perform(post("/api/v1/quotes").header("Authorization", ADMIN).contentType(MediaType.APPLICATION_JSON)
                        .content(quote("INTELIPOST", "Q-981", "42.50", ",\"orderReference\":\"PED-5521\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("REVISED"))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.revision").value(2));

        assertThat(admin.queryForObject("select current_revision from quotes.quote", Integer.class)).isEqualTo(2);
        assertThat(admin.queryForList("select amount::text from quotes.quote_revision order by revision", String.class))
                .containsExactly("39.90", "42.50");
        assertThat(events("quote.revised")).hasSize(1);
        assertThat(events("quote.revised").get(0).getData()).containsEntry("revision", 2);
    }

    @Test
    void sameExternalIdInADifferentSourceOrTenantIsAnotherQuote() throws Exception {
        register(quote("INTELIPOST", "Q-1", "10", ",\"orderReference\":\"A\""));
        register(quote("VTEX", "Q-1", "10", ",\"orderReference\":\"A\""));
        mvc.perform(post("/api/v1/quotes").header("Authorization", TwoTenants.bearerB("TENANT_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(quote("INTELIPOST", "Q-1", "10", ",\"orderReference\":\"A\"")))
                .andExpect(status().isCreated());
        assertThat(admin.queryForObject("select count(*) from quotes.quote", Integer.class)).isEqualTo(3);
    }

    @Test
    void onlyTenantAdminsRegisterQuotes() throws Exception {
        mvc.perform(post("/api/v1/quotes").header("Authorization", TwoTenants.bearerA("AUDITOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(quote("VTEX", "Q-1", "10", ",\"orderReference\":\"A\"")))
                .andExpect(status().isForbidden());
    }

    @Test
    void linksByKeyWhenTheNfeIsAlreadyProcessed() throws Exception {
        NfeFixture fixture = NfeFixture.builder().number(31).issuerCnpj(Cnpjs.of("45723174", "0001"));
        var nfe = fixture.build();
        var docId = processNfe(TwoTenants.A, nfe);

        String id = JsonPath.read(register(quote("VTEX", "Q-key", "39.90", ",\"nfeKey\":\"" + nfe.accessKey() + "\"")), "$.id");

        var row = admin.queryForMap("select link_status, link_method, linked_nfe_document_id from quotes.quote where id = ?::uuid", id);
        assertThat(row.get("link_status")).isEqualTo("LINKED");
        assertThat(row.get("link_method")).isEqualTo("KEY");
        assertThat(row.get("linked_nfe_document_id")).isEqualTo(docId);
        assertThat(events("quote.linked")).hasSize(1);
        assertThat(events("quote.linked").get(0).getData()).containsEntry("documentId", docId.toString()).containsEntry("quoteId", id);
    }

    @Test
    void linksByOrderReferenceWhenASingleNfeCarriesIt() throws Exception {
        var docId = processNfe(TwoTenants.A, NfeFixture.builder().number(32).orderReference("PED-5521").build());
        String id = JsonPath.read(register(quote("INTELIPOST", "Q-981", "39.90", ",\"orderReference\":\"PED-5521\"")), "$.id");

        var row = admin.queryForMap("select link_status, link_method, linked_nfe_document_id from quotes.quote where id = ?::uuid", id);
        assertThat(row.get("link_status")).isEqualTo("LINKED");
        assertThat(row.get("link_method")).isEqualTo("ORDER");
        assertThat(row.get("linked_nfe_document_id")).isEqualTo(docId);
    }

    @Test
    void anOrderReferenceMatchingTwoNfesIsAmbiguousAndUnlinked() throws Exception {
        processNfe(TwoTenants.A, NfeFixture.builder().number(33).orderReference("PED-77").build());
        processNfe(TwoTenants.A, NfeFixture.builder().number(34).orderReference("PED-77").build());
        String id = JsonPath.read(register(quote("INTELIPOST", "Q-77", "39.90", ",\"orderReference\":\"PED-77\"")), "$.id");

        var row = admin.queryForMap("select link_status, linked_nfe_document_id from quotes.quote where id = ?::uuid", id);
        assertThat(row.get("link_status")).isEqualTo("AMBIGUOUS_ORDER_REFERENCE");
        assertThat(row.get("linked_nfe_document_id")).isNull();
        assertThat(events("quote.linked")).isEmpty();
    }

    @Test
    void aQuoteWithoutAnyMatchingNfeStaysUnlinked() throws Exception {
        String id = JsonPath.read(register(quote("VTEX", "Q-none", "39.90", ",\"orderReference\":\"PED-NONE\"")), "$.id");
        assertThat(admin.queryForObject("select link_status from quotes.quote where id = ?::uuid", String.class, id)).isEqualTo("UNLINKED");
    }
}

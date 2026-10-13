package br.com.easyaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.freight.fixtures.FreightTableFixtures;
import br.com.easyaudit.platform.contract.EventContractValidator;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.testsupport.KeycloakContainerSupport;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.RabbitContainerSupport;
import br.com.easyaudit.tenancy.infrastructure.web.HmacAuthenticationFilter;
import com.jayway.jsonpath.JsonPath;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Teste end-to-end com PostgreSQL 17, RabbitMQ 4 e Keycloak 26 reais via Testcontainers.
 * Cobre o fluxo completo do MVP de auditoria:
 * Onboarding do tenant → credencial → NF-e e CT-e por webhook → cotação → importação e ativação
 * da tabela → auditoria → contestação → resolução → lote → exportação → baixa → indicadores do dashboard.
 */
@SpringBootTest(classes = EasyAuditApplication.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=none",
                "easyaudit.credentials.keys.1=sLiLfgX+mcAzrDvOzyoOPnmAxdbgPDz3bnmG1zJkGlk=",
                "easyaudit.credentials.active-version=1",
                "easyaudit.test.real-keycloak=true"
        })
@AutoConfigureMockMvc
class EndToEndIT {

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
        RabbitContainerSupport.register(registry);
        KeycloakContainerSupport.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    JdbcTemplate jdbc;

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private MockHttpServletRequestBuilder signed(String path, String keyId, String secret, String body) {
        String ts = Long.toString(Instant.now().getEpochSecond());
        String signature = "sha256=" + HmacAuthenticationFilter.sign(secret, ts, body.getBytes(StandardCharsets.UTF_8));
        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header(HmacAuthenticationFilter.KEY_ID_HEADER, keyId)
                .header(HmacAuthenticationFilter.TIMESTAMP_HEADER, ts)
                .header(HmacAuthenticationFilter.SIGNATURE_HEADER, signature);
    }

    @Test
    @DisplayName("Fluxo completo de ponta a ponta: do onboarding do tenant até a baixa do lote e dashboard")
    void completeEndToEndFlow() throws Exception {
        // 1. Onboarding do tenant pelo PLATFORM_ADMIN via Keycloak real
        String platformAdminToken = KeycloakContainerSupport.bearer("admin.plataforma", "senha123");
        String createTenantBody = """
                {
                    "name": "Lojista E2E Alpha",
                    "slug": "lojista-e2e-alpha",
                    "cnpjs": ["45.723.174/0001-10"]
                }
                """;

        String tenantResponse = mvc.perform(post("/admin/v1/tenants")
                        .header("Authorization", platformAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTenantBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.slug").value("lojista-e2e-alpha"))
                .andExpect(jsonPath("$.cnpjs[0]").value("45723174000110"))
                .andExpect(OpenApiResponseValidator.conformsToContract())
                .andReturn().getResponse().getContentAsString();

        String tenantId = JsonPath.read(tenantResponse, "$.id");
        assertThat(tenantId).isNotBlank();

        // Configura o tenant_id dos usuários de teste no Keycloak
        KeycloakContainerSupport.setUserTenantId("admin.a", tenantId);
        KeycloakContainerSupport.setUserTenantId("auditor.a", tenantId);
        KeycloakContainerSupport.setUserTenantId("financeiro.a", tenantId);

        // Obtém tokens JWT reais do Keycloak para cada papel
        String tenantAdminToken = KeycloakContainerSupport.bearer("admin.a", "senha123");
        String auditorToken = KeycloakContainerSupport.bearer("auditor.a", "senha123");
        String financeToken = KeycloakContainerSupport.bearer("financeiro.a", "senha123");

        // 2. Criação de credencial de integração (TENANT_ADMIN)
        String credentialResponse = mvc.perform(post("/api/v1/tenant/integration-credentials")
                        .header("Authorization", tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Webhook de Documentos E2E\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.keyId").isNotEmpty())
                .andExpect(jsonPath("$.secret").isNotEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract())
                .andReturn().getResponse().getContentAsString();

        String keyId = JsonPath.read(credentialResponse, "$.keyId");
        String secret = JsonPath.read(credentialResponse, "$.secret");
        assertThat(keyId).isNotBlank();
        assertThat(secret).isNotBlank();

        // 3. Importação e ativação da tabela de frete da transportadora (11.222.333/0001-81)
        String carrierResponse = mvc.perform(post("/api/v1/carriers")
                        .header("Authorization", tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cnpj\": \"11222333000181\", \"name\": \"Transportadora Rápidos E2E\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract())
                .andReturn().getResponse().getContentAsString();

        String carrierId = JsonPath.read(carrierResponse, "$.id");

        FreightTableContent tableContent = FreightTableFixtures.tableT();
        tableContent.setValidFrom(LocalDate.of(2026, 1, 1));
        String tableBody = "{\"carrierId\":\"" + carrierId + "\",\"content\":" + mapper.writeValueAsString(tableContent) + "}";

        String tableResponse = mvc.perform(post("/api/v1/freight-tables")
                        .header("Authorization", tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tableBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(OpenApiResponseValidator.conformsToContract())
                .andReturn().getResponse().getContentAsString();

        String tableId = JsonPath.read(tableResponse, "$.id");

        mvc.perform(post("/api/v1/freight-tables/" + tableId + "/activate")
                        .header("Authorization", tenantAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // 4. Ingestão de NF-e e CT-e por webhook assinado com HMAC
        int docNum = 9501;
        FiscalXml nfe = NfeFixture.builder()
                .number(docNum)
                .issuerCnpj("45723174000110")
                .volume(1, "10.000", "12.500")
                .build();

        // CT-e cobrando 80.00 (valor da tabela é 66.02 -> divergência de 13.98)
        FiscalXml cte = CteFixture.builder()
                .number(docNum)
                .issuerCnpj("11222333000181")
                .nfeKey(nfe.accessKey())
                .destination("3550308", "Sao Paulo", "SP", "01310100")
                .quantity("01", "PESO BRUTO", "12.5")
                .quantity("00", "VOLUME", "0.05")
                .cargoValue("1000.00")
                .icms("00", "80.00", "12.00", "9.60")
                .totalAmount("80.00")
                .component("FREIGHT_WEIGHT", "55.00")
                .component("AD_VALOREM", "3.00")
                .component("GRIS", "2.00")
                .component("TOLL", "3.10")
                .component("DISPATCH_FEE", "5.00")
                .component("ICMS", "9.60")
                .build();

        String docsBody = "{\"documents\":["
                + "{\"name\":\"nfe.xml\",\"contentBase64\":\"" + b64(nfe.bytes()) + "\"},"
                + "{\"name\":\"cte.xml\",\"contentBase64\":\"" + b64(cte.bytes()) + "\"}"
                + "]}";

        mvc.perform(signed("/webhooks/v1/documents", keyId, secret, docsBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[1].status").value("ACCEPTED"))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // 5. Cotação por webhook assinada com HMAC
        String quoteBody = "{\"source\":\"INTELIPOST\",\"externalId\":\"Q-9501\",\"carrierCnpj\":\"11222333000181\","
                + "\"service\":\"Rodoviário\",\"amount\":66.02,\"quotedAt\":\"2026-10-09T10:00:00-03:00\","
                + "\"destinationCep\":\"01310100\",\"weightKg\":12.5,\"cargoValue\":1000,\"deliveryDays\":5,"
                + "\"nfeKey\":\"" + nfe.accessKey() + "\"}";

        mvc.perform(signed("/webhooks/v1/quotes", keyId, secret, quoteBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // 6. Auditoria (processamento assíncrono via RabbitMQ)
        // Aguarda a normalização e a avaliação do caso de auditoria
        String caseIdHolder[] = new String[1];
        Long caseVersionHolder[] = new Long[1];

        Awaitility.await().atMost(15, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            String auditsResponse = mvc.perform(get("/api/v1/audits")
                            .header("Authorization", auditorToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            List<?> items = JsonPath.read(auditsResponse, "$.items");
            assertThat(items).isNotEmpty();

            String status = JsonPath.read(auditsResponse, "$.items[0].status");
            assertThat(status).isEqualTo("DIVERGENT");

            caseIdHolder[0] = JsonPath.read(auditsResponse, "$.items[0].id");
            caseVersionHolder[0] = ((Number) JsonPath.read(auditsResponse, "$.items[0].version")).longValue();
            Number divergence = JsonPath.read(auditsResponse, "$.items[0].divergenceAmount");
            assertThat(new BigDecimal(divergence.toString())).isEqualByComparingTo("13.98");
        });

        String caseId = caseIdHolder[0];
        long version = caseVersionHolder[0];

        // 7. Contestação do caso divergente pelo AUDITOR
        String disputeBody = "{\"version\":" + version
                + ",\"category\":\"VALUE_ABOVE_REFERENCE\",\"description\":\"Frete cobrado acima da tabela negociada\","
                + "\"amountDue\":66.02}";

        String disputeResponse = mvc.perform(post("/api/v1/audits/" + caseId + "/dispute")
                        .header("Authorization", auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(disputeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISPUTED"))
                .andExpect(OpenApiResponseValidator.conformsToContract())
                .andReturn().getResponse().getContentAsString();

        long resolvedVersion = ((Number) JsonPath.read(disputeResponse, "$.version")).longValue();

        // 8. Resolução da contestação com aprovação em 66.02
        String resolveBody = "{\"version\":" + resolvedVersion
                + ",\"agreedAmount\":66.02,\"note\":\"Transportadora aceitou o valor de tabela\"}";

        String resolveResponse = mvc.perform(post("/api/v1/audits/" + caseId + "/resolve")
                        .header("Authorization", auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resolveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAmount").value(66.02))
                .andExpect(OpenApiResponseValidator.conformsToContract())
                .andReturn().getResponse().getContentAsString();

        // 9. Lote de pagamento pelo papel FINANCE
        String batchBody = "{\"dueDate\":\"2026-11-10\",\"description\":\"Lote E2E Novembro\",\"caseIds\":[\"" + caseId + "\"]}";

        String batchResponse = mvc.perform(post("/api/v1/payment-batches")
                        .header("Authorization", financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(batchBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batch.status").value("OPEN"))
                .andExpect(jsonPath("$.batch.caseCount").value(1))
                .andExpect(jsonPath("$.batch.chargedTotal").value(80.00))
                .andExpect(jsonPath("$.batch.approvedTotal").value(66.02))
                .andExpect(jsonPath("$.batch.savingsTotal").value(13.98))
                .andExpect(OpenApiResponseValidator.conformsToContract())
                .andReturn().getResponse().getContentAsString();

        String batchId = JsonPath.read(batchResponse, "$.batch.id");

        // 10. Exportação do lote em CSV (UTF-8 com BOM, delimitador ;)
        MvcResult exportResult = mvc.perform(get("/api/v1/payment-batches/" + batchId + "/export")
                        .header("Authorization", financeToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("lote-" + batchId + ".csv")))
                .andReturn();

        byte[] csvBytes = exportResult.getResponse().getContentAsByteArray();
        assertThat(csvBytes).isNotEmpty();
        // BOM UTF-8
        assertThat(csvBytes[0]).isEqualTo((byte) 0xEF);
        assertThat(csvBytes[1]).isEqualTo((byte) 0xBB);
        assertThat(csvBytes[2]).isEqualTo((byte) 0xBF);
        String csvContent = new String(csvBytes, StandardCharsets.UTF_8);
        assertThat(csvContent).contains("11222333000181").contains("80,00").contains("66,02").contains("13,98");

        // 11. Baixa manual do lote pelo papel FINANCE
        String paymentBody = "{\"paidOn\":\"2026-10-10\",\"reference\":\"TED-E2E-20261010-001\"}";

        mvc.perform(post("/api/v1/payment-batches/" + batchId + "/payment")
                        .header("Authorization", financeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch.status").value("PAID"))
                .andExpect(jsonPath("$.batch.paidOn").value("2026-10-10"))
                .andExpect(jsonPath("$.batch.paymentReference").value("TED-E2E-20261010-001"))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // 12. Indicadores consolidados do dashboard
        mvc.perform(get("/api/v1/dashboard/summary?from=2026-10-01&to=2026-10-31")
                        .header("Authorization", auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(1))
                .andExpect(jsonPath("$.totalCharged").value(80.00))
                .andExpect(jsonPath("$.totalApproved").value(66.02))
                .andExpect(jsonPath("$.savings").value(13.98))
                .andExpect(jsonPath("$.byStatus[?(@.status=='PAID')].count").value(1))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        mvc.perform(get("/api/v1/dashboard/carriers?from=2026-10-01&to=2026-10-31")
                        .header("Authorization", auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].carrierCnpj").value("11222333000181"))
                .andExpect(jsonPath("$.items[0].caseCount").value(1))
                .andExpect(jsonPath("$.items[0].chargedTotal").value(80.00))
                .andExpect(jsonPath("$.items[0].savings").value(13.98))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // 13. Validação de contrato global de todos os eventos publicados contra o schema
        EventContractValidator eventValidator = new EventContractValidator();
        List<String> publishedEvents = jdbc.queryForList(
                "SELECT serialized_event FROM messaging.event_publication", String.class);
        assertThat(publishedEvents).isNotEmpty();
        for (String eventJson : publishedEvents) {
            assertThat(eventValidator.validateEnvelope(eventJson))
                    .as("Envelope de evento válido: " + eventJson)
                    .isEmpty();
        }
    }
}

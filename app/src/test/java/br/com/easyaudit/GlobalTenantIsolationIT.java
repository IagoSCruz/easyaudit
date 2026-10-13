package br.com.easyaudit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import br.com.easyaudit.audit.fixtures.AuditCaseFixtures;
import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.freight.fixtures.FreightTableFixtures;
import br.com.easyaudit.platform.contract.OpenApiResponseValidator;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.RabbitContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.tenancy.TenancyFixtures;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Suíte de isolamento entre tenants:
 * Percorre todos os endpoints GET por id e as listagens com um usuário do tenant B
 * contra os dados persistidos do tenant A.
 * Verifica que todos retornam 404 (para consultas por id) ou listas vazias (para listagens).
 */
@SpringBootTest(classes = EasyAuditApplication.class,
        properties = {
                "spring.jpa.hibernate.ddl-auto=none",
                "easyaudit.credentials.keys.1=sLiLfgX+mcAzrDvOzyoOPnmAxdbgPDz3bnmG1zJkGlk=",
                "easyaudit.credentials.active-version=1",
                "easyaudit.messaging.listeners.enabled=false"
        })
@AutoConfigureMockMvc
class GlobalTenantIsolationIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
        RabbitContainerSupport.register(registry);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper mapper;

    @Autowired
    IngestDocument ingest;

    JdbcTemplate admin;

    // Tokens para Tenant A e Tenant B com todas as permissões necessárias
    static final String USER_A = TwoTenants.bearerA("TENANT_ADMIN", "AUDITOR", "FINANCE", "VIEWER");
    static final String USER_B = TwoTenants.bearerB("TENANT_ADMIN", "AUDITOR", "FINANCE", "VIEWER");

    UUID documentId;
    UUID carrierId;
    UUID aliasId;
    UUID tableId;
    UUID quoteId;
    UUID caseId;
    UUID batchId;

    @BeforeEach
    void setUp() throws Exception {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate settlement.batch_export, settlement.batch_item, settlement.payment_batch,"
                + " audit.case_transition, audit.audit_revision, audit.case_nfe, audit.dispute, audit.audit_case,"
                + " quotes.quote_revision, quotes.quote,"
                + " freight.component_alias, freight.freight_table, freight.carrier,"
                + " documents.raw_document, documents.document_link, documents.pending_cancellation, documents.document cascade");

        TenancyFixtures.seedTwoTenants(admin);

        // 1. Dados do Tenant A:
        // Credencial de integração para Tenant A
        mvc.perform(post("/api/v1/tenant/integration-credentials")
                        .header("Authorization", USER_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Credencial A\"}"))
                .andExpect(status().isCreated());

        // Carrier para Tenant A
        String carrierResp = mvc.perform(post("/api/v1/carriers")
                        .header("Authorization", USER_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cnpj\": \"11222333000181\", \"name\": \"Carrier A\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        carrierId = UUID.fromString(JsonPath.read(carrierResp, "$.id"));

        // Alias para Carrier A
        String aliasResp = mvc.perform(post("/api/v1/carriers/" + carrierId + "/component-aliases")
                        .header("Authorization", USER_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alias\": \"TAXA_EXTRA\", \"componentCode\": \"DISPATCH_FEE\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        aliasId = UUID.fromString(JsonPath.read(aliasResp, "$.id"));

        // Freight Table para Tenant A
        FreightTableContent tableContent = FreightTableFixtures.tableT();
        tableContent.setValidFrom(LocalDate.of(2026, 1, 1));
        String tableResp = mvc.perform(post("/api/v1/freight-tables")
                        .header("Authorization", USER_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrierId\":\"" + carrierId + "\",\"content\":" + mapper.writeValueAsString(tableContent) + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        tableId = UUID.fromString(JsonPath.read(tableResp, "$.id"));

        // Document para Tenant A
        var nfe = NfeFixture.builder().number(8801).issuerCnpj("11222333000181").build();
        documentId = TenantContext.callAs(TwoTenants.A, () ->
                ingest.ingest(Source.UPLOAD, "nfe-a.xml", nfe.bytes()).documentId());

        // Quote para Tenant A
        String quoteBody = "{\"source\":\"VTEX\",\"externalId\":\"Q-8801\",\"carrierCnpj\":\"11222333000181\","
                + "\"service\":\"Express\",\"amount\":50.00,\"quotedAt\":\"2026-10-09T10:00:00-03:00\","
                + "\"destinationCep\":\"20040020\",\"weightKg\":5.0,\"cargoValue\":500,\"deliveryDays\":3,"
                + "\"nfeKey\":\"" + nfe.accessKey() + "\"}";
        String quoteResp = mvc.perform(post("/api/v1/quotes")
                        .header("Authorization", USER_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(quoteBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        quoteId = UUID.fromString(JsonPath.read(quoteResp, "$.id"));

        // Caso de auditoria para Tenant A
        caseId = AuditCaseFixtures.aCase(admin, TwoTenants.A.value())
                .status("APPROVED")
                .charged("80.00")
                .approved("80.00")
                .carrierId(carrierId)
                .insert();

        // Payment batch para Tenant A
        String batchResp = mvc.perform(post("/api/v1/payment-batches")
                        .header("Authorization", USER_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dueDate\":\"2026-11-10\",\"description\":\"Lote A\",\"caseIds\":[\"" + caseId + "\"]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        batchId = UUID.fromString(JsonPath.read(batchResp, "$.batch.id"));
    }

    @Test
    @DisplayName("Usuário do Tenant B recebe 404 em todos os endpoints GET por ID de recursos do Tenant A")
    void tenantBCannotAccessTenantAResourcesById() throws Exception {
        // Documentos
        mvc.perform(get("/api/v1/documents/" + documentId).header("Authorization", USER_B))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        mvc.perform(get("/api/v1/documents/" + documentId + "/xml").header("Authorization", USER_B))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Transportadoras e aliases
        mvc.perform(get("/api/v1/carriers/" + carrierId).header("Authorization", USER_B))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        mvc.perform(get("/api/v1/carriers/" + carrierId + "/component-aliases").header("Authorization", USER_B))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        mvc.perform(get("/api/v1/carriers/" + carrierId + "/audit-policy").header("Authorization", USER_B))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Tabelas de frete
        mvc.perform(get("/api/v1/freight-tables/" + tableId).header("Authorization", USER_B))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Cotações
        mvc.perform(get("/api/v1/quotes/" + quoteId).header("Authorization", USER_B))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Casos de auditoria
        mvc.perform(get("/api/v1/audits/" + caseId).header("Authorization", USER_B))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Lotes de pagamento e exportação
        mvc.perform(get("/api/v1/payment-batches/" + batchId).header("Authorization", USER_B))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        mvc.perform(get("/api/v1/payment-batches/" + batchId + "/export").header("Authorization", USER_B))
                .andExpect(status().isNotFound())
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }

    @Test
    @DisplayName("Usuário do Tenant B recebe listas vazias ou contendo apenas dados de B em todas as listagens")
    void tenantBListingsDoNotContainTenantAData() throws Exception {
        // Documentos
        mvc.perform(get("/api/v1/documents").header("Authorization", USER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // CNPJs do tenant: não deve conter o CNPJ do Tenant A (11222333)
        mvc.perform(get("/api/v1/tenant/cnpjs").header("Authorization", USER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.cnpj=='11222333')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.cnpj=='99888777')]").exists())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Credenciais de integração
        mvc.perform(get("/api/v1/tenant/integration-credentials").header("Authorization", USER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Transportadoras
        mvc.perform(get("/api/v1/carriers").header("Authorization", USER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Tabelas de frete
        mvc.perform(get("/api/v1/freight-tables").header("Authorization", USER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Cotações
        mvc.perform(get("/api/v1/quotes").header("Authorization", USER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Casos de auditoria
        mvc.perform(get("/api/v1/audits").header("Authorization", USER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Lotes de pagamento
        mvc.perform(get("/api/v1/payment-batches").header("Authorization", USER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Dashboard Summary: todos os números zerados para B
        mvc.perform(get("/api/v1/dashboard/summary").header("Authorization", USER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseCount").value(0))
                .andExpect(jsonPath("$.totalCharged").value(0))
                .andExpect(jsonPath("$.totalApproved").value(0))
                .andExpect(jsonPath("$.savings").value(0))
                .andExpect(OpenApiResponseValidator.conformsToContract());

        // Dashboard Carriers: ranking vazio para B
        mvc.perform(get("/api/v1/dashboard/carriers").header("Authorization", USER_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(OpenApiResponseValidator.conformsToContract());
    }
}

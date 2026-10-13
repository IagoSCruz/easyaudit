package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.tenancy.TenancyFixtures;
import br.com.easyaudit.tenancy.application.CredentialService;
import br.com.easyaudit.tenancy.application.TenantService;
import br.com.easyaudit.tenancy.infrastructure.web.HmacAuthenticationFilter;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(classes = DocumentsTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureMockMvc
class DocumentWebhookIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    TenantService tenants;
    @Autowired
    CredentialService credentials;

    JdbcTemplate admin;
    String keyId;
    String secret;

    @BeforeEach
    void setUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate documents.raw_document, documents.pending_cancellation, documents.document cascade");
        TenancyFixtures.seedTwoTenants(admin);
        tenants.addCnpj(TwoTenants.A.value(), "45723174");
        var created = credentials.create(TwoTenants.A.value(), "Intelipost");
        keyId = created.credential().keyId();
        secret = created.secret();
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private MockHttpServletRequestBuilder signed(String body) {
        String ts = Long.toString(Instant.now().getEpochSecond());
        String signature = "sha256=" + HmacAuthenticationFilter.sign(secret, ts, body.getBytes(StandardCharsets.UTF_8));
        return post("/webhooks/v1/documents").contentType(MediaType.APPLICATION_JSON).content(body)
                .header(HmacAuthenticationFilter.KEY_ID_HEADER, keyId)
                .header(HmacAuthenticationFilter.TIMESTAMP_HEADER, ts)
                .header(HmacAuthenticationFilter.SIGNATURE_HEADER, signature);
    }

    @Test
    void validEnvelopeAcceptsDocumentsForTheCredentialsTenant() throws Exception {
        String body = "{\"documents\":[{\"name\":\"nfe-1.xml\",\"contentBase64\":\"" + b64(NfeFixture.builder().number(1).build().bytes())
                + "\"},{\"contentBase64\":\"" + b64(NfeFixture.builder().number(2).build().bytes()) + "\"}]}";

        mvc.perform(signed(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].filename").value("nfe-1.xml"))
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[1].filename").value("documents[1]"))
                .andExpect(jsonPath("$.items[1].status").value("ACCEPTED"));
        assertThat(admin.queryForList("select distinct tenant_id::text from documents.document", String.class))
                .containsExactly(TwoTenants.A.toString());
        assertThat(admin.queryForList("select distinct source from documents.document", String.class)).containsExactly("WEBHOOK");
    }

    @Test
    void resendingTheSameCteReturnsDuplicateWithTheOriginalId() throws Exception {
        String body = "{\"documents\":[{\"contentBase64\":\"" + b64(CteFixture.builder().number(3).build().bytes()) + "\"}]}";
        String first = mvc.perform(signed(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(first, "$.items[0].documentId");

        mvc.perform(signed(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("DUPLICATE"))
                .andExpect(jsonPath("$.items[0].documentId").value(id));
        assertThat(admin.queryForObject("select count(*) from documents.document", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameValidationsAsUploadApplyPerItem() throws Exception {
        String body = "{\"documents\":[{\"name\":\"ok\",\"contentBase64\":\"" + b64(CteFixture.builder().number(4).build().bytes())
                + "\"},{\"name\":\"outra-empresa\",\"contentBase64\":\"" + b64(NfeFixture.builder().issuerCnpj("55555555000191")
                .recipientCnpj("66666666000191").build().bytes()) + "\"},{\"name\":\"lixo\",\"contentBase64\":\"" + b64("nao e xml".getBytes()) + "\"}]}";
        mvc.perform(signed(body)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.items[1].reason").value("NOT_TENANT_DOCUMENT"))
                .andExpect(jsonPath("$.items[2].reason").value("MALFORMED_XML"));
    }

    @Test
    void envelopeWithMoreThan100DocumentsIs413AndAcceptsNothing() throws Exception {
        String one = "{\"contentBase64\":\"" + b64(CteFixture.builder().number(5).build().bytes()) + "\"}";
        String body = "{\"documents\":[" + IntStream.range(0, 101).mapToObj(i -> one).collect(Collectors.joining(",")) + "]}";

        mvc.perform(signed(body)).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(admin.queryForObject("select count(*) from documents.document", Integer.class)).isZero();
    }

    @Test
    void requestWithoutAValidSignatureIs401() throws Exception {
        String body = "{\"documents\":[{\"contentBase64\":\"" + b64(CteFixture.builder().number(6).build().bytes()) + "\"}]}";
        mvc.perform(post("/webhooks/v1/documents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/webhooks/v1/documents").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HmacAuthenticationFilter.KEY_ID_HEADER, keyId)
                        .header(HmacAuthenticationFilter.TIMESTAMP_HEADER, Long.toString(Instant.now().getEpochSecond()))
                        .header(HmacAuthenticationFilter.SIGNATURE_HEADER, "sha256=" + "0".repeat(64)))
                .andExpect(status().isUnauthorized());
        assertThat(admin.queryForObject("select count(*) from documents.document", Integer.class)).isZero();
    }

    @Test
    void eachTenantGetsItsOwnRecordWhenBothSendTheSameNfe() throws Exception {
        var credB = credentials.create(TwoTenants.B.value(), "Webhook B");
        tenants.addCnpj(TwoTenants.B.value(), "99888777");
        String body = "{\"documents\":[{\"contentBase64\":\"" + b64(NfeFixture.builder().number(77).build().bytes()) + "\"}]}";
        mvc.perform(signed(body)).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));

        String ts = Long.toString(Instant.now().getEpochSecond());
        String signature = "sha256=" + HmacAuthenticationFilter.sign(credB.secret(), ts, body.getBytes(StandardCharsets.UTF_8));
        mvc.perform(post("/webhooks/v1/documents").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header(HmacAuthenticationFilter.KEY_ID_HEADER, credB.credential().keyId())
                        .header(HmacAuthenticationFilter.TIMESTAMP_HEADER, ts)
                        .header(HmacAuthenticationFilter.SIGNATURE_HEADER, signature))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status").value("ACCEPTED"));
        assertThat(admin.queryForObject("select count(distinct tenant_id) from documents.document", Integer.class)).isEqualTo(2);
    }
}

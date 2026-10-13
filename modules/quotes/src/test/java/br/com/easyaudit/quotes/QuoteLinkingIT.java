package br.com.easyaudit.quotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.UUID;

import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.messaging.MessagingProperties;
import br.com.easyaudit.platform.messaging.MessagingTopology;
import br.com.easyaudit.platform.messaging.OutboxConfiguration;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.RabbitContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.tenancy.TenancyFixtures;
import br.com.easyaudit.tenancy.application.TenantService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Pipeline completo: ingestão → outbox → normalização → {@code document.normalized} → vínculo de cotações. */
@SpringBootTest(classes = {QuotesTestApplication.class, OutboxConfiguration.class, QuoteLinkingIT.Probe.class},
        properties = {"spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=true",
                "easyaudit.messaging.retry.initial-interval=50ms", "easyaudit.messaging.retry.multiplier=2",
                "easyaudit.messaging.retry.max-interval=500ms"})
@AutoConfigureMockMvc
class QuoteLinkingIT {

    static String jdbcUrl;

    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {
        @Bean
        Declarables quoteLinkedProbe(MessagingProperties properties) {
            return MessagingTopology.consumerQueue("it.quote-linked-probe", properties.deliveryLimit(), "quote.linked");
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
        RabbitContainerSupport.register(registry);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    IngestDocument ingest;
    @Autowired
    TenantService tenants;
    @Autowired
    RabbitTemplate rabbit;

    JdbcTemplate admin;
    static final String ADMIN = TwoTenants.bearerA("TENANT_ADMIN");

    @BeforeEach
    void setUp() {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate quotes.quote_revision, quotes.quote, documents.document_link, documents.raw_document, "
                + "documents.pending_cancellation, documents.document, messaging.processed_event cascade");
        admin.execute("delete from messaging.event_publication");
        TenancyFixtures.seedTwoTenants(admin);
        tenants.addCnpj(TwoTenants.A.value(), "45723174");
        while (rabbit.receive("it.quote-linked-probe", 200) != null) {
            // esvazia a fila de prova
        }
    }

    private String quote(String externalId, String extra) throws Exception {
        String body = "{\"source\":\"INTELIPOST\",\"externalId\":\"" + externalId + "\",\"carrierCnpj\":\"11222333000181\","
                + "\"amount\":39.90,\"quotedAt\":\"2026-10-09T10:00:00-03:00\",\"destinationCep\":\"20040020\"" + extra + "}";
        return JsonPath.read(mvc.perform(post("/api/v1/quotes").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString(), "$.id");
    }

    private UUID receive(FiscalXml xml) throws Exception {
        return TenantContext.callAs(TwoTenants.A, () -> ingest.ingest(Source.UPLOAD, "n.xml", xml.bytes()).documentId());
    }

    private String linkStatus(String quoteId) {
        return admin.queryForObject("select link_status from quotes.quote where id = ?::uuid", String.class, quoteId);
    }

    @Test
    void quoteByOrderReferenceIsLinkedWhenTheNfeArrivesLaterAndTheEventIsPublished() throws Exception {
        String id = quote("Q-5521", ",\"orderReference\":\"PED-5521\"");
        assertThat(linkStatus(id)).isEqualTo("UNLINKED");

        UUID nfeDocument = receive(NfeFixture.builder().number(41).orderReference("PED-5521").build());

        await().atMost(Duration.ofSeconds(30)).until(() -> "LINKED".equals(linkStatus(id)));
        assertThat(admin.queryForObject("select linked_nfe_document_id from quotes.quote where id = ?::uuid", UUID.class, id))
                .isEqualTo(nfeDocument);
        Message message = rabbit.receive("it.quote-linked-probe", 15_000);
        assertThat(message).as("quote.linked publicado").isNotNull();
        assertThat(new br.com.easyaudit.platform.contract.EventContractValidator().validateEnvelope(new String(message.getBody()))).isEmpty();
        assertThat(new String(message.getBody())).contains(id).contains(nfeDocument.toString());
    }

    @Test
    void quoteByNfeKeyIsLinkedWhenTheNfeArrivesLater() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(42).build();
        String id = quote("Q-key", ",\"nfeKey\":\"" + nfe.accessKey() + "\"");
        assertThat(linkStatus(id)).isEqualTo("UNLINKED");

        UUID doc = receive(nfe);

        await().atMost(Duration.ofSeconds(30)).until(() -> "LINKED".equals(linkStatus(id)));
        assertThat(admin.queryForObject("select linked_nfe_document_id from quotes.quote where id = ?::uuid", UUID.class, id)).isEqualTo(doc);
        assertThat(admin.queryForObject("select link_method from quotes.quote where id = ?::uuid", String.class, id)).isEqualTo("KEY");
    }

    @Test
    void aSecondNfeWithTheSameOrderReferenceMakesTheQuoteAmbiguous() throws Exception {
        String id = quote("Q-77", ",\"orderReference\":\"PED-77\"");
        receive(NfeFixture.builder().number(43).orderReference("PED-77").build());
        await().atMost(Duration.ofSeconds(30)).until(() -> "LINKED".equals(linkStatus(id)));

        receive(NfeFixture.builder().number(44).orderReference("PED-77").build());

        await().atMost(Duration.ofSeconds(30)).until(() -> "AMBIGUOUS_ORDER_REFERENCE".equals(linkStatus(id)));
        assertThat(admin.queryForObject("select linked_nfe_document_id from quotes.quote where id = ?::uuid", UUID.class, id)).isNull();
    }

    @Test
    void anAmbiguousReferenceThatArrivesOnTheQuoteSideNeverLinks() throws Exception {
        receive(NfeFixture.builder().number(45).orderReference("PED-88").build());
        receive(NfeFixture.builder().number(46).orderReference("PED-88").build());
        await().atMost(Duration.ofSeconds(30)).until(() -> admin.queryForObject(
                "select count(*) from documents.document where status = 'PROCESSED'", Integer.class) == 2);

        String id = quote("Q-88", ",\"orderReference\":\"PED-88\"");
        assertThat(linkStatus(id)).isEqualTo("AMBIGUOUS_ORDER_REFERENCE");
    }
}

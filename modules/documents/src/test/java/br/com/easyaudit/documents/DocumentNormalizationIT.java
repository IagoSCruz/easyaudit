package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.application.RawDocumentStorage;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.freight.application.CarrierService;
import br.com.easyaudit.freight.application.ComponentAliasService;
import br.com.easyaudit.platform.contract.EventContractValidator;
import br.com.easyaudit.platform.messaging.MessagingProperties;
import br.com.easyaudit.platform.messaging.MessagingTopology;
import br.com.easyaudit.platform.messaging.OutboxConfiguration;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.RabbitContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.tenancy.TenancyFixtures;
import br.com.easyaudit.tenancy.application.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/** Fluxo completo: ingestão → outbox → RabbitMQ → normalização → {@code document.normalized}. */
@SpringBootTest(classes = {DocumentsTestApplication.class, OutboxConfiguration.class,
        DocumentNormalizationIT.Probe.class}, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "easyaudit.messaging.listeners.enabled=true",
        "easyaudit.messaging.retry.initial-interval=50ms",
        "easyaudit.messaging.retry.multiplier=2",
        "easyaudit.messaging.retry.max-interval=500ms"})
@Import(DocumentNormalizationIT.Probe.class)
class DocumentNormalizationIT {

    static String jdbcUrl;
    static final AtomicBoolean STORAGE_DOWN = new AtomicBoolean();

    /** Fila de teste ligada a {@code document.normalized} e um armazenamento que pode "cair" na leitura. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Probe {
        @Bean
        Declarables normalizedProbe(MessagingProperties properties) {
            return MessagingTopology.consumerQueue("it.normalized-probe", properties.deliveryLimit(), "document.normalized");
        }

        @Bean
        static BeanPostProcessor flakyStorage() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof RawDocumentStorage real)) {
                        return bean;
                    }
                    return new RawDocumentStorage() {
                        @Override
                        public void store(UUID documentId, byte[] content) {
                            real.store(documentId, content);
                        }

                        @Override
                        public Optional<byte[]> load(UUID documentId) {
                            if (STORAGE_DOWN.get()) {
                                throw new IllegalStateException("armazenamento indisponível (simulado)");
                            }
                            return real.load(documentId);
                        }
                    };
                }
            };
        }
    }

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
        RabbitContainerSupport.register(registry);
    }

    @Autowired
    IngestDocument ingest;
    @Autowired
    TenantService tenants;
    @Autowired
    CarrierService carriers;
    @Autowired
    ComponentAliasService aliases;
    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    JsonMapper mapper;

    JdbcTemplate admin;

    @BeforeEach
    void setUp() {
        STORAGE_DOWN.set(false);
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate documents.raw_document, documents.pending_cancellation, documents.document, "
                + "freight.component_alias, freight.carrier, messaging.processed_event cascade");
        admin.execute("delete from messaging.event_publication");
        TenancyFixtures.seedTwoTenants(admin);
        tenants.addCnpj(TwoTenants.A.value(), "45723174");
        while (rabbit.receive("it.normalized-probe", 200) != null) {
            // esvazia a fila de prova
        }
    }

    private UUID ingestCte(br.com.easyaudit.documents.fixtures.CteFixture fixture) throws Exception {
        return TenantContext.callAs(TwoTenants.A, () -> ingest.ingest(Source.UPLOAD, "x.xml", fixture.build().bytes()).documentId());
    }

    private String statusOf(UUID id) {
        return admin.queryForObject("select status from documents.document where id = ?", String.class, id);
    }

    @Test
    void cteIsNormalizedAndTheEventIsPublishedWithTheCanonicalModel() throws Exception {
        UUID id = ingestCte(CteFixture.builder().component("Frete-Peso", "100.00").component("TAXA XPTO", "52.37"));

        await().atMost(Duration.ofSeconds(30)).until(() -> "PROCESSED".equals(statusOf(id)));
        var row = admin.queryForMap("select canonical::text as canonical, issued_at, processed_at from documents.document where id = ?", id);
        assertThat(row.get("processed_at")).isNotNull();
        assertThat(row.get("issued_at")).isNotNull();
        var canonical = mapper.readTree((String) row.get("canonical"));
        assertThat(canonical.get("totalAmount").asDouble()).isEqualTo(152.37);
        assertThat(canonical.get("components").get(0).get("code").asString()).isEqualTo("FREIGHT_WEIGHT");
        assertThat(canonical.get("components").get(1).get("code").asString()).isEqualTo("OTHER");
        assertThat(canonical.get("components").get(1).get("originalName").asString()).isEqualTo("TAXA XPTO");

        Message message = rabbit.receive("it.normalized-probe", 15_000);
        assertThat(message).as("document.normalized publicado").isNotNull();
        String body = new String(message.getBody());
        var validator = new EventContractValidator();
        assertThat(validator.validateEnvelope(body)).isEmpty();
        var envelope = mapper.readTree(body);
        assertThat(envelope.get("eventType").asString()).isEqualTo("document.normalized");
        assertThat(envelope.get("tenantId").asString()).isEqualTo(TwoTenants.A.toString());
        assertThat(validator.validatePayload("DocumentNormalizedData", envelope.get("data").toString())).isEmpty();
        assertThat(envelope.get("data").get("documentId").asString()).isEqualTo(id.toString());
        assertThat(envelope.get("data").get("cte").get("components").size()).isEqualTo(2);
    }

    @Test
    void nfeIsNormalizedToo() throws Exception {
        UUID id = TenantContext.callAs(TwoTenants.A, () -> ingest.ingest(Source.UPLOAD, "n.xml",
                NfeFixture.builder().volume(1, "3.000", "3.500").build().bytes()).documentId());
        await().atMost(Duration.ofSeconds(30)).until(() -> "PROCESSED".equals(statusOf(id)));
        var canonical = mapper.readTree(admin.queryForObject("select canonical::text from documents.document where id = ?", String.class, id));
        assertThat(canonical.get("totalGrossWeightKg").asDouble()).isEqualTo(3.5);
        Message message = rabbit.receive("it.normalized-probe", 15_000);
        assertThat(new EventContractValidator().validateEnvelope(new String(message.getBody()))).isEmpty();
    }

    @Test
    void carrierSynonymPrevailsInTheNormalizedComponents() throws Exception {
        var carrier = TenantContext.callAs(TwoTenants.A, () -> carriers.create("11222333000181", "Rápidos"));
        TenantContext.callAs(TwoTenants.A, () -> aliases.create(carrier.id(), "TX EMERGENCIAL", "TDE"));
        UUID id = ingestCte(CteFixture.builder().component("TX EMERGENCIAL", "20.00").component("Frete Peso", "132.37"));

        await().atMost(Duration.ofSeconds(30)).until(() -> "PROCESSED".equals(statusOf(id)));
        var canonical = mapper.readTree(admin.queryForObject("select canonical::text from documents.document where id = ?", String.class, id));
        assertThat(canonical.get("components").get(0).get("code").asString()).isEqualTo("TDE");
        assertThat(canonical.get("components").get(1).get("code").asString()).isEqualTo("FREIGHT_WEIGHT");
    }

    @Test
    void contentErrorsGoStraightToFailedWithTheReasonAndNoEvent() throws Exception {
        UUID id = ingestCte(CteFixture.builder().withoutValuesGroup());

        await().atMost(Duration.ofSeconds(30)).until(() -> "FAILED".equals(statusOf(id)));
        assertThat(admin.queryForObject("select failure_reason from documents.document where id = ?", String.class, id))
                .contains("vPrest");
        assertThat(rabbit.receive("it.normalized-probe", 1500)).isNull();
        // continua baixável
        assertThat(admin.queryForObject("select count(*) from documents.raw_document where document_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void infrastructureErrorsAreRetriedThenTheDlqHandlerMarksFailed() throws Exception {
        STORAGE_DOWN.set(true);
        UUID id = ingestCte(CteFixture.builder());

        await().atMost(Duration.ofSeconds(30)).until(() -> "FAILED".equals(statusOf(id)));
        assertThat(admin.queryForObject("select failure_reason from documents.document where id = ?", String.class, id))
                .contains("após todas as tentativas");
        assertThat(rabbit.receive("it.normalized-probe", 1000)).isNull();
    }
}

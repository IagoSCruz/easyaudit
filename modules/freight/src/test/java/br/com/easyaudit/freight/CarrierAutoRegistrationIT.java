package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import br.com.easyaudit.contracts.events.CanonicalCte;
import br.com.easyaudit.contracts.events.CteType;
import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.contracts.events.EventType;
import br.com.easyaudit.contracts.events.Party;
import br.com.easyaudit.platform.messaging.MessagingHeaders;
import br.com.easyaudit.platform.messaging.MessagingTopology;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.RabbitContainerSupport;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.shared.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(classes = FreightTestApplication.class, properties = "spring.jpa.hibernate.ddl-auto=none")
class CarrierAutoRegistrationIT {

    static String jdbcUrl;

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        jdbcUrl = PostgresContainerSupport.registerApplicationRoles(registry);
        RabbitContainerSupport.register(registry);
    }

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    JsonMapper mapper;

    JdbcTemplate admin;

    @BeforeEach
    void clean() {
        admin = new JdbcTemplate(new DriverManagerDataSource(jdbcUrl, "postgres", "postgres"));
        admin.execute("truncate freight.component_alias, freight.carrier, messaging.processed_event cascade");
    }

    private EventEnvelope normalized(TenantId tenant, String kind, String carrierCnpj, String carrierName) {
        EventEnvelope event = new EventEnvelope();
        event.setEventId(UUID.randomUUID());
        event.setEventType(EventType.DOCUMENT_NORMALIZED);
        event.setSchemaVersion(1);
        event.setOccurredAt(OffsetDateTime.now());
        event.setTenantId(tenant.value());
        CanonicalCte cte = new CanonicalCte();
        cte.setAccessKey("35261011222333000181570010000001231123456780");
        cte.setType(CteType.NORMAL);
        Party carrier = new Party();
        carrier.setCnpj(carrierCnpj);
        carrier.setName(carrierName);
        cte.setCarrier(carrier);
        event.setData(Map.of("documentId", UUID.randomUUID().toString(), "kind", kind,
                "accessKey", cte.getAccessKey(), "cte", mapper.convertValue(cte, Map.class)));
        return event;
    }

    private void publish(EventEnvelope event) {
        rabbit.convertAndSend(MessagingTopology.EVENTS_EXCHANGE, "document.normalized", event, message -> {
            message.getMessageProperties().setMessageId(event.getEventId().toString());
            message.getMessageProperties().setHeader(MessagingHeaders.TENANT_ID, event.getTenantId().toString());
            return message;
        });
    }

    private int carriers() {
        return admin.queryForObject("select count(*) from freight.carrier", Integer.class);
    }

    @Test
    void unknownIssuerIsRegisteredOnceEvenWithDuplicatedAndRepeatedEvents() {
        EventEnvelope event = normalized(TwoTenants.A, "CTE", "11222333000181", "TRANSPORTADORA DO XML LTDA");
        publish(event);
        publish(event); // entrega duplicada: mesmo eventId
        publish(normalized(TwoTenants.A, "CTE", "11222333000181", "OUTRO NOME")); // outro evento, mesmo emitente

        await().atMost(Duration.ofSeconds(20)).until(() -> carriers() == 1);
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).until(() -> carriers() == 1);

        var row = admin.queryForMap("select cnpj, name, auto_registered, tenant_id::text as tenant from freight.carrier");
        assertThat(row.get("cnpj").toString().trim()).isEqualTo("11222333000181");
        assertThat(row.get("name")).isEqualTo("TRANSPORTADORA DO XML LTDA");
        assertThat(row.get("auto_registered")).isEqualTo(true);
        assertThat(row.get("tenant")).isEqualTo(TwoTenants.A.toString());
    }

    @Test
    void sameIssuerInAnotherTenantIsRegisteredSeparately() {
        publish(normalized(TwoTenants.A, "CTE", "11222333000181", "RÁPIDOS"));
        publish(normalized(TwoTenants.B, "CTE", "11222333000181", "RÁPIDOS"));
        await().atMost(Duration.ofSeconds(20)).until(() -> carriers() == 2);
    }

    @Test
    void nfeEventsDoNotRegisterCarriers() throws Exception {
        publish(normalized(TwoTenants.A, "NFE", "11222333000181", "RÁPIDOS"));
        // um CT-e logo depois prova que a fila foi processada
        publish(normalized(TwoTenants.A, "CTE", "45723174000110", "OUTRA"));
        await().atMost(Duration.ofSeconds(20)).until(() -> carriers() == 1);
        assertThat(admin.queryForObject("select cnpj from freight.carrier", String.class).trim()).isEqualTo("45723174000110");
    }

    @Test
    void existingCarrierIsNotChanged() throws Exception {
        // cadastro manual prévio do tenant A
        admin.update("insert into freight.carrier (id, tenant_id, cnpj, name, auto_registered) values (gen_random_uuid(), ?::uuid, '11222333000181', 'NOME MANUAL', false)",
                TwoTenants.A.toString());
        publish(normalized(TwoTenants.A, "CTE", "11222333000181", "NOME DO XML"));
        publish(normalized(TwoTenants.A, "CTE", "45723174000110", "SENTINELA"));
        await().atMost(Duration.ofSeconds(20)).until(() -> carriers() == 2);
        var row = admin.queryForMap("select name, auto_registered from freight.carrier where cnpj = '11222333000181'");
        assertThat(row.get("name")).isEqualTo("NOME MANUAL");
        assertThat(row.get("auto_registered")).isEqualTo(false);
    }
}

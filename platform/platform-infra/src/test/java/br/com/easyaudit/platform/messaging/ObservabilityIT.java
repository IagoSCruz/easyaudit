package br.com.easyaudit.platform.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.RabbitContainerSupport;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(classes = ObservabilityTestApplication.class, properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "management.tracing.sampling.probability=1.0"})
class ObservabilityIT {

    @DynamicPropertySource
    static void infra(DynamicPropertyRegistry registry) {
        PostgresContainerSupport.registerApplicationRoles(registry);
        RabbitContainerSupport.register(registry);
    }

    @Autowired
    RabbitTemplate rabbit;
    @Autowired
    Tracer tracer;

    @Test
    void producerTraceIdAppearsInConsumerProcessing() {
        UUID eventId = UUID.randomUUID();
        String tenant = "11111111-1111-4111-8111-111111111111";
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setMessageId(eventId.toString());
        props.setHeader(MessagingHeaders.TENANT_ID, tenant);
        Message message = MessageBuilder.withBody("{\"x\":1}".getBytes()).andProperties(props).build();

        var span = tracer.nextSpan().name("producer").start();
        String producerTraceId = span.context().traceId();
        try (var ignored = tracer.withSpan(span)) {
            rabbit.send(MessagingTopology.EVENTS_EXCHANGE, "it.observability", message);
        } finally {
            span.end();
        }

        await().atMost(Duration.ofSeconds(15)).until(() -> !ObservabilityTestApplication.SEEN.isEmpty());
        var seen = ObservabilityTestApplication.SEEN.getFirst();
        assertThat(seen.traceId()).isEqualTo(producerTraceId);
        assertThat(seen.mdcTraceId()).isEqualTo(producerTraceId);
        assertThat(seen.mdcTenant()).isEqualTo(tenant);
        assertThat(seen.mdcEventId()).isEqualTo(eventId.toString());
    }
}

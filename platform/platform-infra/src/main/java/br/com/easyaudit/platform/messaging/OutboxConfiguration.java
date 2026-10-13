package br.com.easyaudit.platform.messaging;

import java.time.Duration;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.platform.PlatformDefaultsConfiguration;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.events.EventExternalizationConfiguration;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.modulith.events.RoutingTarget;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Outbox: eventos {@link EventEnvelope} publicados dentro de uma transação são gravados no registro de publicações
 * do Spring Modulith (JDBC, schema {@code messaging}) na mesma transação do efeito e enviados ao RabbitMQ somente
 * depois do commit. A routing key é o {@code eventType}. Publicações incompletas são reenviadas no startup
 * ({@code republish-outstanding-events-on-restart}) e por um job agendado.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(PlatformDefaultsConfiguration.class)
public class OutboxConfiguration {

    @Bean
    EventExternalizationConfiguration eventExternalization() {
        return EventExternalizationConfiguration.externalizing()
                .select(EventEnvelope.class::isInstance)
                .route(EventEnvelope.class, e -> RoutingTarget.forTarget(MessagingTopology.EVENTS_EXCHANGE)
                        .andKey(e.getEventType().getValue()))
                .build();
    }

    /**
     * Antes de publicar, lê {@code eventId} e {@code tenantId} do envelope JSON e preenche o {@code messageId} do
     * AMQP (usado pelos consumidores na idempotência) e o header {@code x-tenant-id}. Remove o {@code __TypeId__}
     * para que o contrato do evento não dependa de nomes de classe Java.
     */
    @Bean
    RabbitTemplateCustomizer envelopeHeaders() {
        JsonMapper mapper = JsonMapper.builder().build();
        MessagePostProcessor processor = message -> {
            MessageProperties props = message.getMessageProperties();
            props.getHeaders().remove("__TypeId__");
            if (props.getMessageId() == null && props.getContentType() != null
                    && props.getContentType().contains("json")) {
                JsonNode root = mapper.readTree(message.getBody());
                if (root.hasNonNull("eventId")) {
                    props.setMessageId(root.get("eventId").asString());
                }
                if (root.hasNonNull("tenantId") && props.getHeader(MessagingHeaders.TENANT_ID) == null) {
                    props.setHeader(MessagingHeaders.TENANT_ID, root.get("tenantId").asString());
                }
            }
            return message;
        };
        return template -> template.addBeforePublishPostProcessors(processor);
    }

    @Bean
    OutboxRepublisher outboxRepublisher(IncompleteEventPublications incomplete,
            @Value("${easyaudit.messaging.outbox.republish-after:PT30S}") Duration after) {
        return new OutboxRepublisher(incomplete, after);
    }

    /** Reenvia publicações incompletas mais antigas que {@code after}. */
    public static class OutboxRepublisher {

        private final IncompleteEventPublications incomplete;
        private final Duration after;

        OutboxRepublisher(IncompleteEventPublications incomplete, Duration after) {
            this.incomplete = incomplete;
            this.after = after;
        }

        @Scheduled(fixedDelayString = "${easyaudit.messaging.outbox.republish-interval:PT1M}")
        public void republish() {
            incomplete.resubmitIncompletePublicationsOlderThan(after);
        }
    }
}

package br.com.easyaudit.platform.messaging;

import java.util.concurrent.atomic.AtomicBoolean;

import br.com.easyaudit.platform.db.FlywayModulesConfiguration;
import br.com.easyaudit.platform.tenant.TenantPersistenceConfiguration;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({MessagingConfiguration.class, OutboxConfiguration.class, FlywayModulesConfiguration.class,
        TenantPersistenceConfiguration.class})
class OutboxTestApplication {

    /** Quando ligado, simula o RabbitMQ rejeitando a publicação. */
    static final AtomicBoolean BROKER_DOWN = new AtomicBoolean();

    @Bean
    Declarables outboxQueue(MessagingProperties properties) {
        return MessagingTopology.consumerQueue("it.outbox", properties.deliveryLimit(), "audit.completed");
    }

    @Bean
    RabbitTemplateCustomizer failingPublisher() {
        return template -> template.addBeforePublishPostProcessors(message -> {
            if (BROKER_DOWN.get()) {
                throw new IllegalStateException("broker indisponível (simulado)");
            }
            return message;
        });
    }
}

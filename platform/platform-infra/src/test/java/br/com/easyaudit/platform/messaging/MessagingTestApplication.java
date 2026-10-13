package br.com.easyaudit.platform.messaging;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.easyaudit.platform.db.FlywayModulesConfiguration;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.tenant.TenantPersistenceConfiguration;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({MessagingConfiguration.class, FlywayModulesConfiguration.class, TenantPersistenceConfiguration.class})
class MessagingTestApplication {

    static final AtomicInteger FAILING_ATTEMPTS = new AtomicInteger();
    static final CopyOnWriteArrayList<String> FAILING_TENANTS = new CopyOnWriteArrayList<>();
    static final AtomicInteger IDEMPOTENT_EFFECTS = new AtomicInteger();
    static final CopyOnWriteArrayList<String> SEEN_TENANTS = new CopyOnWriteArrayList<>();

    @Bean
    Declarables testQueues(MessagingProperties properties) {
        var all = new java.util.ArrayList<org.springframework.amqp.core.Declarable>();
        all.addAll(MessagingTopology.consumerQueue("it.failing", properties.deliveryLimit(), "it.failing").getDeclarables());
        all.addAll(MessagingTopology.consumerQueue("it.idempotent", properties.deliveryLimit(), "it.idempotent").getDeclarables());
        all.addAll(MessagingTopology.consumerQueue("it.tenant", properties.deliveryLimit(), "it.tenant").getDeclarables());
        return new Declarables(all);
    }

    @Bean
    Listeners listeners(IdempotentConsumer idempotent) {
        return new Listeners(idempotent);
    }

    static class Listeners {
        private final IdempotentConsumer idempotent;

        Listeners(IdempotentConsumer idempotent) {
            this.idempotent = idempotent;
        }

        @RabbitListener(queues = "it.failing", containerFactory = MessagingConfiguration.LISTENER_FACTORY)
        void failing(Map<String, Object> payload) {
            FAILING_ATTEMPTS.incrementAndGet();
            FAILING_TENANTS.add(TenantContext.current().map(Object::toString).orElse("none"));
            throw new IllegalStateException("falha permanente");
        }

        @RabbitListener(queues = "it.idempotent", containerFactory = MessagingConfiguration.LISTENER_FACTORY)
        void idempotent(Message message, Map<String, Object> payload) {
            UUID eventId = UUID.fromString(message.getMessageProperties().getMessageId());
            idempotent.runOnce("it.idempotent", eventId, IDEMPOTENT_EFFECTS::incrementAndGet);
        }

        @RabbitListener(queues = "it.tenant", containerFactory = MessagingConfiguration.LISTENER_FACTORY)
        void tenant(Map<String, Object> payload) {
            SEEN_TENANTS.add(TenantContext.current().map(Object::toString).orElse("none"));
        }
    }
}

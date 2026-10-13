package br.com.easyaudit.platform.messaging;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import br.com.easyaudit.platform.db.FlywayModulesConfiguration;
import br.com.easyaudit.platform.tenant.TenantPersistenceConfiguration;
import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({MessagingConfiguration.class, FlywayModulesConfiguration.class, TenantPersistenceConfiguration.class})
class ObservabilityTestApplication {

    record Seen(String traceId, String mdcTraceId, String mdcTenant, String mdcEventId) {
    }

    static final CopyOnWriteArrayList<Seen> SEEN = new CopyOnWriteArrayList<>();

    @Bean
    Declarables observabilityQueue(MessagingProperties properties) {
        return MessagingTopology.consumerQueue("it.observability", properties.deliveryLimit(), "it.observability");
    }

    @Bean
    Consumer consumer(Tracer tracer) {
        return new Consumer(tracer);
    }

    static class Consumer {
        private final Tracer tracer;

        Consumer(Tracer tracer) {
            this.tracer = tracer;
        }

        @RabbitListener(queues = "it.observability", containerFactory = MessagingConfiguration.LISTENER_FACTORY)
        void handle(Map<String, Object> payload) {
            var span = tracer.currentSpan();
            SEEN.add(new Seen(span == null ? null : span.context().traceId(), MDC.get("traceId"),
                    MDC.get("tenantId"), MDC.get("eventId")));
        }
    }
}

package br.com.easyaudit.settlement.infrastructure.messaging;

import br.com.easyaudit.platform.messaging.MessagingProperties;
import br.com.easyaudit.platform.messaging.MessagingTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Filas do módulo settlement. */
@Configuration(proxyBeanMethods = false)
class SettlementMessagingConfiguration {

    static final String STATUS_CHANGED_QUEUE = "settlement.audit-status-changed";

    @Bean
    Declarables settlementQueues(MessagingProperties properties) {
        return MessagingTopology.consumerQueue(STATUS_CHANGED_QUEUE, properties.deliveryLimit(), "audit.status-changed");
    }
}

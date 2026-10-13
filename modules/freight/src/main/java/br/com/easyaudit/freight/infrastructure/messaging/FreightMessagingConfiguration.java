package br.com.easyaudit.freight.infrastructure.messaging;

import br.com.easyaudit.platform.messaging.MessagingProperties;
import br.com.easyaudit.platform.messaging.MessagingTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Filas do módulo freight. */
@Configuration(proxyBeanMethods = false)
class FreightMessagingConfiguration {

    static final String CARRIER_AUTO_REGISTER_QUEUE = "freight.carrier-auto-register";

    @Bean
    Declarables freightQueues(MessagingProperties properties) {
        return MessagingTopology.consumerQueue(CARRIER_AUTO_REGISTER_QUEUE, properties.deliveryLimit(),
                "document.normalized");
    }
}

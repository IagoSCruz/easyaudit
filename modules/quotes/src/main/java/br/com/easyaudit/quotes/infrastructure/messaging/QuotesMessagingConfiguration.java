package br.com.easyaudit.quotes.infrastructure.messaging;

import br.com.easyaudit.platform.messaging.MessagingProperties;
import br.com.easyaudit.platform.messaging.MessagingTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Filas do módulo quotes. */
@Configuration(proxyBeanMethods = false)
class QuotesMessagingConfiguration {

    static final String LINK_NFE_QUEUE = "quotes.link-nfe";

    @Bean
    Declarables quotesQueues(MessagingProperties properties) {
        return MessagingTopology.consumerQueue(LINK_NFE_QUEUE, properties.deliveryLimit(), "document.normalized");
    }
}

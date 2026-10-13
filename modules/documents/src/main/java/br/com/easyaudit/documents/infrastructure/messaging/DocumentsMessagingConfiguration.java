package br.com.easyaudit.documents.infrastructure.messaging;

import br.com.easyaudit.platform.messaging.MessagingProperties;
import br.com.easyaudit.platform.messaging.MessagingTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Filas do módulo documents. */
@Configuration(proxyBeanMethods = false)
class DocumentsMessagingConfiguration {

    static final String NORMALIZE_QUEUE = "documents.normalize";
    static final String NORMALIZE_DLQ = NORMALIZE_QUEUE + MessagingTopology.DLQ_SUFFIX;

    @Bean
    Declarables documentsQueues(MessagingProperties properties) {
        return MessagingTopology.consumerQueue(NORMALIZE_QUEUE, properties.deliveryLimit(), "document.received");
    }
}

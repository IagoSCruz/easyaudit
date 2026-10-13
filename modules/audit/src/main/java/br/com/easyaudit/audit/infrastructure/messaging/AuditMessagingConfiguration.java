package br.com.easyaudit.audit.infrastructure.messaging;

import br.com.easyaudit.platform.messaging.MessagingProperties;
import br.com.easyaudit.platform.messaging.MessagingTopology;
import org.springframework.amqp.core.Declarables;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Filas do módulo audit. */
@Configuration(proxyBeanMethods = false)
class AuditMessagingConfiguration {

    static final String CTE_NORMALIZED_QUEUE = "audit.cte-normalized";
    static final String LINK_RESOLVED_QUEUE = "audit.link-resolved";
    static final String QUOTE_CHANGED_QUEUE = "audit.quote-changed";
    static final String TABLE_ACTIVATED_QUEUE = "audit.table-activated";
    static final String CTE_CANCELLED_QUEUE = "audit.cte-cancelled";

    @Bean
    Declarables auditQueues(MessagingProperties properties) {
        int limit = properties.deliveryLimit();
        var all = new java.util.ArrayList<org.springframework.amqp.core.Declarable>();
        all.addAll(MessagingTopology.consumerQueue(CTE_NORMALIZED_QUEUE, limit, "document.normalized").getDeclarables());
        all.addAll(MessagingTopology.consumerQueue(LINK_RESOLVED_QUEUE, limit, "document.link-resolved").getDeclarables());
        all.addAll(MessagingTopology.consumerQueue(QUOTE_CHANGED_QUEUE, limit, "quote.linked", "quote.revised")
                .getDeclarables());
        all.addAll(MessagingTopology.consumerQueue(TABLE_ACTIVATED_QUEUE, limit, "freight-table.activated")
                .getDeclarables());
        all.addAll(MessagingTopology.consumerQueue(CTE_CANCELLED_QUEUE, limit, "document.cancelled").getDeclarables());
        return new org.springframework.amqp.core.Declarables(all);
    }
}

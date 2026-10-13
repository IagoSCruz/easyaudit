package br.com.easyaudit.audit.infrastructure.messaging;

import java.util.UUID;

import br.com.easyaudit.audit.application.AuditCases;
import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.platform.messaging.IdempotentConsumer;
import br.com.easyaudit.platform.messaging.MessagingConfiguration;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de {@code document.normalized} (CT-e): cria o caso de auditoria e faz a primeira auditoria. Idempotente
 * por {@code eventId} e, além disso, por documento (um caso por CT-e).
 */
@Component
class CteNormalizedListener {

    private final AuditCases cases;
    private final IdempotentConsumer idempotent;

    CteNormalizedListener(AuditCases cases, IdempotentConsumer idempotent) {
        this.cases = cases;
        this.idempotent = idempotent;
    }

    @RabbitListener(queues = AuditMessagingConfiguration.CTE_NORMALIZED_QUEUE,
            containerFactory = MessagingConfiguration.LISTENER_FACTORY)
    void onDocumentNormalized(EventEnvelope envelope) {
        if (!"CTE".equals(envelope.getData().get("kind"))) {
            return;
        }
        UUID documentId = UUID.fromString((String) envelope.getData().get("documentId"));
        idempotent.runOnce(AuditMessagingConfiguration.CTE_NORMALIZED_QUEUE, envelope.getEventId(),
                () -> cases.createAndAudit(documentId));
    }
}

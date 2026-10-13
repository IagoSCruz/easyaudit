package br.com.easyaudit.audit.infrastructure.messaging;

import java.util.UUID;

import br.com.easyaudit.audit.application.CaseCancellation;
import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.platform.messaging.IdempotentConsumer;
import br.com.easyaudit.platform.messaging.MessagingConfiguration;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consome {@code document.cancelled} (cancelamento fiscal ou substituição) de CT-es e cancela o caso. Se o caso ainda
 * não existe (a normalização e o cancelamento correm em filas diferentes), a mensagem falha e volta pelo retry; um
 * caso criado depois já nasce cancelado.
 */
@Component
class CteCancelledListener {

    private final CaseCancellation cancellation;
    private final IdempotentConsumer idempotent;

    CteCancelledListener(CaseCancellation cancellation, IdempotentConsumer idempotent) {
        this.cancellation = cancellation;
        this.idempotent = idempotent;
    }

    @RabbitListener(queues = AuditMessagingConfiguration.CTE_CANCELLED_QUEUE,
            containerFactory = MessagingConfiguration.LISTENER_FACTORY)
    void onDocumentCancelled(EventEnvelope envelope) {
        var data = envelope.getData();
        if (!"CTE".equals(data.get("kind"))) {
            return;
        }
        UUID documentId = UUID.fromString((String) data.get("documentId"));
        String reason = CaseCancellation.reasonOf((String) data.get("reason"));
        idempotent.runOnce(AuditMessagingConfiguration.CTE_CANCELLED_QUEUE, envelope.getEventId(), () -> {
            if (cancellation.cancelByCte(documentId, reason).isEmpty()) {
                throw new IllegalStateException("O caso do CT-e " + documentId + " ainda não foi criado");
            }
        });
    }
}

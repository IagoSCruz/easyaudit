package br.com.easyaudit.documents.infrastructure.messaging;

import java.util.UUID;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.documents.application.ApplyCancellations;
import br.com.easyaudit.documents.application.NormalizeDocument;
import br.com.easyaudit.platform.messaging.IdempotentConsumer;
import br.com.easyaudit.platform.messaging.MessagingConfiguration;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumidor de {@code document.received}: normaliza o documento. Idempotente por {@code eventId}. Mensagens que
 * esgotam as tentativas vão para a DLQ, onde {@link #onDeadLetter} marca o documento como {@code FAILED}.
 */
@Component
class DocumentReceivedListener {

    private final NormalizeDocument normalize;
    private final ApplyCancellations cancellations;
    private final IdempotentConsumer idempotent;

    DocumentReceivedListener(NormalizeDocument normalize, ApplyCancellations cancellations,
            IdempotentConsumer idempotent) {
        this.normalize = normalize;
        this.cancellations = cancellations;
        this.idempotent = idempotent;
    }

    @RabbitListener(queues = DocumentsMessagingConfiguration.NORMALIZE_QUEUE,
            containerFactory = MessagingConfiguration.LISTENER_FACTORY)
    void onDocumentReceived(EventEnvelope envelope) {
        idempotent.runOnce(DocumentsMessagingConfiguration.NORMALIZE_QUEUE, envelope.getEventId(), () -> {
            UUID id = UUID.fromString((String) envelope.getData().get("documentId"));
            if (Boolean.TRUE.equals(envelope.getData().get("cancellation"))) {
                cancellations.onEventReceived(id);
                return;
            }
            normalize.normalize(id);
        });
    }

    /** Falha definitiva: depois de todas as tentativas, o documento fica {@code FAILED} e continua baixável. */
    @RabbitListener(queues = DocumentsMessagingConfiguration.NORMALIZE_DLQ,
            containerFactory = MessagingConfiguration.LISTENER_FACTORY)
    void onDeadLetter(EventEnvelope envelope) {
        idempotent.runOnce(DocumentsMessagingConfiguration.NORMALIZE_DLQ, envelope.getEventId(), () -> {
            if (Boolean.TRUE.equals(envelope.getData().get("cancellation"))) {
                return;
            }
            normalize.failPermanently(UUID.fromString((String) envelope.getData().get("documentId")),
                    "Falha no processamento do documento após todas as tentativas.");
        });
    }
}

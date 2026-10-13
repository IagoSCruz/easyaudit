package br.com.easyaudit.audit.infrastructure.messaging;

import java.time.LocalDate;
import java.util.UUID;

import br.com.easyaudit.audit.application.Reaudits;
import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.platform.messaging.IdempotentConsumer;
import br.com.easyaudit.platform.messaging.MessagingConfiguration;
import br.com.easyaudit.quotes.api.QuoteLookup;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumidores da reauditoria: {@code document.link-resolved}, {@code quote.linked}/{@code quote.revised} e
 * {@code freight-table.activated}. Cada caso é reauditado na própria transação; os que não estão em estado
 * auditável são ignorados.
 */
@Component
class ReauditListener {

    private final Reaudits reaudits;
    private final QuoteLookup quotes;
    private final IdempotentConsumer idempotent;

    ReauditListener(Reaudits reaudits, QuoteLookup quotes, IdempotentConsumer idempotent) {
        this.reaudits = reaudits;
        this.quotes = quotes;
        this.idempotent = idempotent;
    }

    @RabbitListener(queues = AuditMessagingConfiguration.LINK_RESOLVED_QUEUE,
            containerFactory = MessagingConfiguration.LISTENER_FACTORY)
    void onLinkResolved(EventEnvelope envelope) {
        UUID cteDocumentId = uuid(envelope, "documentId");
        idempotent.runOnce(AuditMessagingConfiguration.LINK_RESOLVED_QUEUE, envelope.getEventId(),
                () -> reaudits.onLinkResolved(cteDocumentId));
    }

    @RabbitListener(queues = AuditMessagingConfiguration.QUOTE_CHANGED_QUEUE,
            containerFactory = MessagingConfiguration.LISTENER_FACTORY)
    void onQuoteChanged(EventEnvelope envelope) {
        idempotent.runOnce(AuditMessagingConfiguration.QUOTE_CHANGED_QUEUE, envelope.getEventId(), () -> {
            // quote.linked traz o documento da NF-e; quote.revised só o id da cotação
            UUID nfeDocument = envelope.getData().containsKey("documentId") ? uuid(envelope, "documentId")
                    : quotes.linkedNfeDocument(uuid(envelope, "quoteId")).orElse(null);
            if (nfeDocument != null) {
                reaudits.onQuoteChanged(nfeDocument);
            }
        });
    }

    /**
     * A reauditoria de uma tabela ativada pode envolver milhares de casos. Cada caso roda na sua transação, então
     * esta mensagem não usa a transação única da idempotência: reentregar a mensagem pula os casos que já têm uma
     * revisão posterior ao evento.
     */
    @RabbitListener(queues = AuditMessagingConfiguration.TABLE_ACTIVATED_QUEUE,
            containerFactory = MessagingConfiguration.LISTENER_FACTORY)
    void onTableActivated(EventEnvelope envelope) {
        var data = envelope.getData();
        LocalDate validTo = data.get("validTo") == null ? null : LocalDate.parse((String) data.get("validTo"));
        reaudits.onTableActivated(uuid(envelope, "carrierId"), LocalDate.parse((String) data.get("validFrom")), validTo,
                envelope.getOccurredAt());
    }

    private static UUID uuid(EventEnvelope envelope, String field) {
        return UUID.fromString((String) envelope.getData().get(field));
    }
}

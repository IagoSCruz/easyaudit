package br.com.easyaudit.settlement.infrastructure.messaging;

import java.util.UUID;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.platform.messaging.IdempotentConsumer;
import br.com.easyaudit.platform.messaging.MessagingConfiguration;
import br.com.easyaudit.settlement.application.PaymentBatches;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Casos cancelados saem do lote {@code OPEN} em que estiverem, e os totais do lote são recalculados. */
@Component
class CaseStatusChangedListener {

    private final PaymentBatches batches;
    private final IdempotentConsumer idempotent;

    CaseStatusChangedListener(PaymentBatches batches, IdempotentConsumer idempotent) {
        this.batches = batches;
        this.idempotent = idempotent;
    }

    @RabbitListener(queues = SettlementMessagingConfiguration.STATUS_CHANGED_QUEUE,
            containerFactory = MessagingConfiguration.LISTENER_FACTORY)
    void onStatusChanged(EventEnvelope envelope) {
        var data = envelope.getData();
        if (!"CANCELLED".equals(data.get("toStatus"))) {
            return;
        }
        UUID caseId = UUID.fromString((String) data.get("caseId"));
        idempotent.runOnce(SettlementMessagingConfiguration.STATUS_CHANGED_QUEUE, envelope.getEventId(),
                () -> batches.onCaseCancelled(caseId));
    }
}

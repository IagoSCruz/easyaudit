package br.com.easyaudit.quotes.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.contracts.events.EventType;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publica {@code quote.linked} e {@code quote.revised} na transação corrente (outbox transacional). */
@Component
public class QuoteEventPublisher {

    private final ApplicationEventPublisher events;

    public QuoteEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** A cotação foi vinculada à NF-e (documento) indicada. */
    public void linked(UUID quoteId, UUID nfeDocumentId, BigDecimal amount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quoteId", quoteId.toString());
        data.put("documentId", nfeDocumentId.toString());
        data.put("amount", amount);
        publish(EventType.QUOTE_LINKED, data);
    }

    /** A cotação ganhou uma nova revisão vigente. */
    public void revised(UUID quoteId, int revision, BigDecimal amount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quoteId", quoteId.toString());
        data.put("revision", revision);
        data.put("amount", amount);
        publish(EventType.QUOTE_REVISED, data);
    }

    private void publish(EventType type, Map<String, Object> data) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId(UuidV7.next());
        envelope.setEventType(type);
        envelope.setSchemaVersion(1);
        envelope.setOccurredAt(OffsetDateTime.now());
        envelope.setTenantId(TenantContext.require().value());
        envelope.setData(data);
        events.publishEvent(envelope);
    }
}

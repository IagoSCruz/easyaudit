package br.com.easyaudit.freight.application;

import java.time.LocalDate;
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

/** Publica os eventos do módulo freight na transação corrente (outbox transacional). */
@Component
public class FreightEventPublisher {

    private final ApplicationEventPublisher events;

    public FreightEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** {@code freight-table.activated}: a transportadora, a versão ativada e o período em que ela vale. */
    public void tableActivated(UUID carrierId, UUID tableVersionId, LocalDate validFrom, LocalDate validTo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("carrierId", carrierId.toString());
        data.put("tableVersionId", tableVersionId.toString());
        data.put("validFrom", validFrom.toString());
        if (validTo != null) {
            data.put("validTo", validTo.toString());
        }
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId(UuidV7.next());
        envelope.setEventType(EventType.FREIGHT_TABLE_ACTIVATED);
        envelope.setSchemaVersion(1);
        envelope.setOccurredAt(OffsetDateTime.now());
        envelope.setTenantId(TenantContext.require().value());
        envelope.setData(data);
        events.publishEvent(envelope);
    }
}

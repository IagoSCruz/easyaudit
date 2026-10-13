package br.com.easyaudit.audit.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.easyaudit.audit.domain.AuditOutcome.Classification;
import br.com.easyaudit.audit.domain.CaseStatus;
import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.contracts.events.EventType;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Publica {@code audit.completed} e {@code audit.status-changed} na transação corrente (outbox transacional). */
@Component
public class AuditEventPublisher {

    private final ApplicationEventPublisher events;

    public AuditEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** Uma revisão de auditoria foi concluída. */
    public void completed(UUID caseId, UUID revisionId, Classification classification, BigDecimal charged,
            BigDecimal reference, List<String> failedChecks) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseId", caseId.toString());
        data.put("revisionId", revisionId.toString());
        data.put("classification", classification.name());
        data.put("chargedAmount", charged);
        if (reference != null) {
            data.put("referenceAmount", reference);
        }
        data.put("failedChecks", failedChecks);
        publish(EventType.AUDIT_COMPLETED, data);
    }

    /** O caso mudou de estado. */
    public void statusChanged(UUID caseId, CaseStatus from, CaseStatus to, String actor, BigDecimal approvedAmount) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("caseId", caseId.toString());
        data.put("fromStatus", from.name());
        data.put("toStatus", to.name());
        data.put("actor", actor);
        data.put("changedAt", OffsetDateTime.now().toString());
        if (approvedAmount != null) {
            data.put("approvedAmount", approvedAmount);
        }
        publish(EventType.AUDIT_STATUS_CHANGED, data);
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

package br.com.easyaudit.documents.application;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.contracts.events.EventType;
import br.com.easyaudit.documents.domain.DocumentKind;
import br.com.easyaudit.documents.domain.DocumentLink.LinkType;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Publica os eventos de integração do módulo na transação corrente (outbox transacional). O envelope leva o
 * tenant do contexto.
 */
@Component
public class DocumentEventPublisher {

    private final ApplicationEventPublisher events;

    public DocumentEventPublisher(ApplicationEventPublisher events) {
        this.events = events;
    }

    public void received(UUID id, DocumentKind kind, String accessKey, boolean cancellation) {
        publish(EventType.DOCUMENT_RECEIVED, Map.of("documentId", id.toString(), "kind", kind.name(),
                "accessKey", accessKey, "cancellation", cancellation));
    }

    /** {@code canonical} já convertido em mapa; entra em {@code cte} ou {@code nfe} conforme o tipo. */
    public void normalized(UUID id, DocumentKind kind, String accessKey, Map<String, Object> canonical) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", id.toString());
        data.put("kind", kind.name());
        data.put("accessKey", accessKey);
        data.put(kind == DocumentKind.CTE ? "cte" : "nfe", canonical);
        publish(EventType.DOCUMENT_NORMALIZED, data);
    }

    /** O vínculo do documento {@code fromDocumentId} foi resolvido para o documento de destino. */
    public void linkResolved(UUID fromDocumentId, LinkType type, String targetKey, UUID targetDocumentId) {
        publish(EventType.DOCUMENT_LINK_RESOLVED, Map.of("documentId", fromDocumentId.toString(),
                "linkType", type.name(), "targetKey", targetKey, "targetDocumentId", targetDocumentId.toString()));
    }

    public void cancelled(UUID id, DocumentKind kind, String accessKey) {
        cancelled(id, kind, accessKey, "CANCELLED");
    }

    /** @param reason {@code CANCELLED} (cancelamento fiscal) ou {@code SUBSTITUTED} (substituído por outro CT-e) */
    public void cancelled(UUID id, DocumentKind kind, String accessKey, String reason) {
        publish(EventType.DOCUMENT_CANCELLED, Map.of("documentId", id.toString(), "kind", kind.name(),
                "accessKey", accessKey, "reason", reason));
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

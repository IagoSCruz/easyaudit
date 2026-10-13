package br.com.easyaudit.documents.application;

import java.util.List;
import java.util.UUID;

import br.com.easyaudit.documents.domain.DocumentKind;
import br.com.easyaudit.documents.domain.Lifecycle;
import br.com.easyaudit.documents.domain.StoredDocument;
import br.com.easyaudit.documents.infrastructure.persistence.CancellationRepository;
import br.com.easyaudit.documents.infrastructure.persistence.CancellationRepository.PendingEvent;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentRepository;
import org.springframework.stereotype.Service;

/**
 * Aplica eventos de cancelamento aceitos. Um evento é aplicado na hora se o documento já foi processado no tenant;
 * senão fica pendente e é aplicado logo depois que o documento é normalizado. Cada aplicação marca o documento como
 * {@code CANCELLED} e publica {@code document.cancelled} (uma vez por documento).
 */
@Service
public class ApplyCancellations {

    private final DocumentRepository documents;
    private final CancellationRepository cancellations;
    private final DocumentEventPublisher publisher;

    public ApplyCancellations(DocumentRepository documents, CancellationRepository cancellations,
            DocumentEventPublisher publisher) {
        this.documents = documents;
        this.cancellations = cancellations;
        this.publisher = publisher;
    }

    /** Chamado quando o evento de cancelamento foi recebido (consumidor de {@code document.received}). */
    public void onEventReceived(UUID eventId) {
        cancellations.findPending(eventId).ifPresent(event ->
                documents.findProcessedIdByKey(event.targetKind(), event.accessKey())
                        .flatMap(documents::findById)
                        .ifPresent(document -> cancel(document, List.of(event))));
    }

    /** Chamado quando um documento termina de ser normalizado: aplica os cancelamentos que o esperavam. */
    public void applyPendingFor(StoredDocument document) {
        List<PendingEvent> pending = cancellations.findPendingFor(document.kind(), document.accessKey());
        if (!pending.isEmpty()) {
            cancel(document, pending);
        }
    }

    private void cancel(StoredDocument document, List<PendingEvent> events) {
        events.forEach(e -> cancellations.markApplied(e.id()));
        if (documents.findById(document.id()).map(StoredDocument::lifecycle).orElse(Lifecycle.CANCELLED)
                == Lifecycle.CANCELLED) {
            return; // já cancelado: nada a notificar de novo
        }
        documents.markLifecycle(document.id(), Lifecycle.CANCELLED);
        publisher.cancelled(document.id(), DocumentKind.valueOf(document.kind().name()), document.accessKey());
    }
}

package br.com.easyaudit.documents.application;

import java.util.List;
import java.util.UUID;

import br.com.easyaudit.contracts.api.model.CanonicalCte;
import br.com.easyaudit.contracts.api.model.CteType;
import br.com.easyaudit.documents.domain.DocumentKind;
import br.com.easyaudit.documents.domain.DocumentLink;
import br.com.easyaudit.documents.domain.DocumentLink.LinkType;
import br.com.easyaudit.documents.domain.Lifecycle;
import br.com.easyaudit.documents.domain.StoredDocument;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentLinkRepository;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentRepository;
import org.springframework.stereotype.Service;

/**
 * Vínculos entre documentos. Um vínculo nasce pendente e é resolvido quando o documento de destino é processado, em
 * qualquer ordem de chegada. Resolver um vínculo que estava pendente publica {@code document.link-resolved} para o
 * documento de origem; um vínculo que já nasce resolvido não gera evento. Um CT-e substituído passa a
 * {@code SUBSTITUTED}.
 */
@Service
public class LinkDocuments {

    private final DocumentRepository documents;
    private final DocumentLinkRepository links;
    private final DocumentEventPublisher publisher;

    public LinkDocuments(DocumentRepository documents, DocumentLinkRepository links, DocumentEventPublisher publisher) {
        this.documents = documents;
        this.links = links;
        this.publisher = publisher;
    }

    /** Chamado quando um CT-e termina de ser normalizado. */
    public void onCteNormalized(StoredDocument cte, CanonicalCte canonical) {
        // vínculos que saem do CT-e: NF-es referenciadas e o CT-e complementado/substituído
        for (String nfeKey : canonical.getReferencedNfeKeys()) {
            link(cte.id(), LinkType.CTE_NFE, DocumentKind.NFE, nfeKey);
        }
        if (canonical.getRelatedCteKey() != null) {
            LinkType type = canonical.getType() == CteType.SUBSTITUTE ? LinkType.SUBSTITUTES : LinkType.COMPLEMENTS;
            link(cte.id(), type, DocumentKind.CTE, canonical.getRelatedCteKey());
        }
        // vínculos de outros CT-es que estavam esperando por este
        resolvePendingTo(cte, DocumentKind.CTE);
    }

    /** Chamado quando uma NF-e termina de ser normalizada. */
    public void onNfeNormalized(StoredDocument nfe) {
        resolvePendingTo(nfe, DocumentKind.NFE);
    }

    private void link(UUID from, LinkType type, DocumentKind targetKind, String targetKey) {
        UUID target = documents.findProcessedIdByKey(targetKind, targetKey).orElse(null);
        links.insertIfAbsent(from, type, targetKind, targetKey, target);
        if (target != null && type == LinkType.SUBSTITUTES) {
            markSubstituted(target, targetKey);
        }
    }

    private void resolvePendingTo(StoredDocument target, DocumentKind kind) {
        List<DocumentLink> pending = links.findPendingByTarget(kind, target.accessKey());
        for (DocumentLink link : pending) {
            links.markResolved(link.id(), target.id());
            if (link.type() == LinkType.SUBSTITUTES) {
                markSubstituted(target.id(), target.accessKey());
            }
            publisher.linkResolved(link.fromDocumentId(), link.type(), link.targetKey(), target.id());
        }
    }

    /** O CT-e original passa a {@code SUBSTITUTED} e a substituição é publicada como cancelamento (uma vez). */
    private void markSubstituted(UUID cteId, String accessKey) {
        Lifecycle current = documents.findById(cteId).map(StoredDocument::lifecycle).orElse(Lifecycle.CANCELLED);
        if (current != Lifecycle.ACTIVE) {
            return; // já cancelado ou já substituído
        }
        documents.markLifecycle(cteId, Lifecycle.SUBSTITUTED);
        publisher.cancelled(cteId, DocumentKind.CTE, accessKey, "SUBSTITUTED");
    }
}

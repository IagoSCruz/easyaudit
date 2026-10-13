package br.com.easyaudit.documents.application;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import br.com.easyaudit.contracts.api.model.CanonicalCte;
import br.com.easyaudit.contracts.api.model.CanonicalNfe;
import br.com.easyaudit.documents.domain.DocumentKind;
import br.com.easyaudit.documents.domain.ProcessingStatus;
import br.com.easyaudit.documents.domain.StoredDocument;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentRepository;
import br.com.easyaudit.documents.infrastructure.xml.CanonicalMapper;
import br.com.easyaudit.documents.infrastructure.xml.NormalizationException;
import br.com.easyaudit.documents.infrastructure.xml.layout.LayoutReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Normalização assíncrona: converte o XML original no modelo canônico, grava em {@code jsonb} com as colunas-chave,
 * marca {@code PROCESSED} e publica {@code document.normalized} pelo outbox.
 *
 * <p>Erros de <b>conteúdo</b> (XML fora do layout, campo obrigatório ausente) levam direto a {@code FAILED} com o
 * motivo e não são repetidos. Erros de <b>infraestrutura</b> (banco, armazenamento) propagam como exceção, para o
 * retry do consumidor e, esgotadas as tentativas, a DLQ.
 */
@Service
public class NormalizeDocument {

    private static final Logger log = LoggerFactory.getLogger(NormalizeDocument.class);

    private final DocumentRepository documents;
    private final RawDocumentStorage storage;
    private final ComponentClassifier classifier;
    private final DocumentEventPublisher publisher;
    private final LinkDocuments linkDocuments;
    private final ApplyCancellations cancellations;
    private final JsonMapper mapper;

    public NormalizeDocument(DocumentRepository documents, RawDocumentStorage storage, ComponentClassifier classifier,
            DocumentEventPublisher publisher, LinkDocuments linkDocuments, ApplyCancellations cancellations,
            JsonMapper mapper) {
        this.documents = documents;
        this.storage = storage;
        this.classifier = classifier;
        this.publisher = publisher;
        this.linkDocuments = linkDocuments;
        this.cancellations = cancellations;
        this.mapper = mapper;
    }

    /** Roda no tenant do contexto corrente e na transação corrente. */
    public void normalize(UUID documentId) {
        StoredDocument document = documents.findById(documentId).orElse(null);
        if (document == null) {
            log.warn("Documento {} não encontrado para normalizar (outro tenant ou removido)", documentId);
            return;
        }
        if (document.status() != ProcessingStatus.RECEIVED) {
            return; // já processado ou já falhou: o reprocessamento é idempotente
        }
        byte[] raw = storage.load(documentId)
                .orElseThrow(() -> new IllegalStateException("XML original do documento " + documentId + " ausente"));

        Object canonical;
        OffsetDateTime issuedAt;
        try {
            if (document.kind() == DocumentKind.CTE) {
                CanonicalCte cte = CanonicalMapper.toCte(LayoutReader.readCte(raw), classifier.forDocument());
                canonical = cte;
                issuedAt = cte.getIssuedAt();
            } else {
                CanonicalNfe nfe = CanonicalMapper.toNfe(LayoutReader.readNfe(raw));
                canonical = nfe;
                issuedAt = nfe.getIssuedAt();
            }
        } catch (NormalizationException | LayoutReader.LayoutException e) {
            log.info("Documento {} falhou na normalização: {}", documentId, e.getMessage());
            documents.markFailed(documentId, e.getMessage());
            return;
        }

        documents.markProcessed(documentId, mapper.writeValueAsString(canonical), issuedAt);
        publisher.normalized(documentId, document.kind(), document.accessKey(), toMap(canonical));
        if (canonical instanceof CanonicalCte cte) {
            linkDocuments.onCteNormalized(document, cte);
        } else {
            linkDocuments.onNfeNormalized(document);
        }
        // um cancelamento que chegou antes do documento vale logo depois da normalização
        cancellations.applyPendingFor(document);
    }

    /** Marca como {@code FAILED} um documento que ainda está {@code RECEIVED}; usado pelo handler da DLQ. */
    public void failPermanently(UUID documentId, String reason) {
        documents.findById(documentId).filter(d -> d.status() == ProcessingStatus.RECEIVED)
                .ifPresent(d -> documents.markFailed(documentId, reason));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object canonical) {
        return mapper.convertValue(canonical, Map.class);
    }
}

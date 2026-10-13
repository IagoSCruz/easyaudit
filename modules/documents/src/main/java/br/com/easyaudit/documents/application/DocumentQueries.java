package br.com.easyaudit.documents.application;

import java.util.List;
import java.util.UUID;

import br.com.easyaudit.documents.domain.DocumentLink;
import br.com.easyaudit.documents.domain.StoredDocument;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentLinkRepository;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consultas de documentos do tenant corrente (a RLS esconde os de outros tenants). */
@Service
public class DocumentQueries {

    /** Página de resultados. */
    public record Page(List<StoredDocument> items, int page, int size, long totalElements) {
        public int totalPages() {
            return (int) Math.ceil((double) totalElements / size);
        }
    }

    /** XML original com o hash registrado na ingestão. */
    public record RawXml(StoredDocument document, byte[] content) {
    }

    /** Documento com o modelo canônico (se processado) e os vínculos, de saída e de entrada. */
    public record DocumentDetail(StoredDocument document, String canonicalJson, List<DocumentLink> outbound,
            List<DocumentLinkRepository.Inbound> inbound) {
    }

    private final DocumentRepository documents;
    private final DocumentLinkRepository links;
    private final RawDocumentStorage storage;

    public DocumentQueries(DocumentRepository documents, DocumentLinkRepository links, RawDocumentStorage storage) {
        this.documents = documents;
        this.links = links;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public DocumentDetail detail(UUID id) {
        StoredDocument document = get(id);
        return new DocumentDetail(document, documents.findCanonical(id).orElse(null), links.findFrom(id),
                links.findInbound(id));
    }

    @Transactional(readOnly = true)
    public Page search(DocumentRepository.Filter filter, int page, int size) {
        return new Page(documents.search(filter, size, (long) page * size), page, size, documents.count(filter));
    }

    @Transactional(readOnly = true)
    public StoredDocument get(UUID id) {
        return documents.findById(id).orElseThrow(DocumentNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public RawXml rawXml(UUID id) {
        StoredDocument document = get(id);
        byte[] content = storage.load(id).orElseThrow(DocumentNotFoundException::new);
        return new RawXml(document, content);
    }
}

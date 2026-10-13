package br.com.easyaudit.documents.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.contracts.api.model.CanonicalCte;
import br.com.easyaudit.contracts.api.model.CanonicalNfe;
import br.com.easyaudit.documents.api.DocumentCatalog;
import br.com.easyaudit.documents.domain.DocumentKind;
import br.com.easyaudit.documents.domain.StoredDocument;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentLinkRepository;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
class DefaultDocumentCatalog implements DocumentCatalog {

    private final DocumentRepository documents;
    private final DocumentLinkRepository links;
    private final JsonMapper mapper;

    DefaultDocumentCatalog(DocumentRepository documents, DocumentLinkRepository links, JsonMapper mapper) {
        this.documents = documents;
        this.links = links;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CanonicalDocument> find(UUID documentId) {
        return documents.findById(documentId).map(this::toCanonical);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CanonicalDocument> findProcessed(Kind kind, String accessKey) {
        return documents.findProcessedIdByKey(DocumentKind.valueOf(kind.name()), accessKey)
                .flatMap(documents::findById).map(this::toCanonical);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NfeRef> findProcessedNfesByOrderReference(String orderReference) {
        return documents.findProcessedNfesByOrderReference(orderReference).stream()
                .map(d -> new NfeRef(d.id(), d.accessKey())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Link> linksFrom(UUID documentId) {
        return links.findFrom(documentId).stream().map(l -> new Link(LinkType.valueOf(l.type().name()), l.targetKey(),
                l.resolved(), l.targetDocumentId())).toList();
    }

    private CanonicalDocument toCanonical(StoredDocument d) {
        CanonicalCte cte = null;
        CanonicalNfe nfe = null;
        String json = documents.findCanonical(d.id()).orElse(null);
        if (json != null) {
            if (d.kind() == DocumentKind.CTE) {
                cte = mapper.readValue(json, CanonicalCte.class);
            } else {
                nfe = mapper.readValue(json, CanonicalNfe.class);
            }
        }
        return new CanonicalDocument(d.id(), Kind.valueOf(d.kind().name()), d.accessKey(),
                Status.valueOf(d.status().name()), Lifecycle.valueOf(d.lifecycle().name()), cte, nfe);
    }
}

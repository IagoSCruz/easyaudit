package br.com.easyaudit.documents.infrastructure.web;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import br.com.easyaudit.contracts.api.model.CanonicalCte;
import br.com.easyaudit.contracts.api.model.CanonicalNfe;
import br.com.easyaudit.contracts.api.model.DocumentKind;
import br.com.easyaudit.contracts.api.model.DocumentLifecycle;
import br.com.easyaudit.contracts.api.model.DocumentLink;
import br.com.easyaudit.contracts.api.model.DocumentLinkType;
import br.com.easyaudit.contracts.api.model.DocumentResponse;
import br.com.easyaudit.contracts.api.model.DocumentStatus;
import br.com.easyaudit.contracts.api.model.DocumentSummary;
import br.com.easyaudit.documents.application.DocumentQueries.DocumentDetail;
import br.com.easyaudit.documents.domain.StoredDocument;
import tools.jackson.databind.json.JsonMapper;

final class DocumentMapper {

    private DocumentMapper() {
    }

    static DocumentSummary toSummary(StoredDocument d) {
        DocumentSummary summary = new DocumentSummary(d.id(), DocumentKind.valueOf(d.kind().name()),
                DocumentStatus.valueOf(d.status().name()), DocumentLifecycle.valueOf(d.lifecycle().name()),
                d.accessKey(), d.receivedAt().withOffsetSameInstant(ZoneOffset.UTC));
        if (d.processedAt() != null) {
            summary.setProcessedAt(d.processedAt().withOffsetSameInstant(ZoneOffset.UTC));
        }
        summary.setFailureReason(d.failureReason());
        return summary;
    }

    static DocumentResponse toResponse(DocumentDetail detail, JsonMapper mapper) {
        StoredDocument d = detail.document();
        List<DocumentLink> links = new ArrayList<>();
        for (var link : detail.outbound()) {
            DocumentLink out = new DocumentLink(DocumentLinkType.valueOf(link.type().name()), link.targetKey(),
                    link.resolved());
            out.setTargetDocumentId(link.targetDocumentId());
            links.add(out);
        }
        for (var in : detail.inbound()) {
            DocumentLinkType type = in.type() == br.com.easyaudit.documents.domain.DocumentLink.LinkType.SUBSTITUTES
                    ? DocumentLinkType.SUBSTITUTED_BY : DocumentLinkType.COMPLEMENTED_BY;
            DocumentLink inverse = new DocumentLink(type, in.fromAccessKey(), true);
            inverse.setTargetDocumentId(in.fromDocumentId());
            links.add(inverse);
        }
        DocumentResponse response = new DocumentResponse(d.id(), DocumentKind.valueOf(d.kind().name()),
                DocumentStatus.valueOf(d.status().name()), DocumentLifecycle.valueOf(d.lifecycle().name()),
                d.accessKey(), d.receivedAt().withOffsetSameInstant(ZoneOffset.UTC), d.rawSha256(), links);
        if (d.processedAt() != null) {
            response.setProcessedAt(d.processedAt().withOffsetSameInstant(ZoneOffset.UTC));
        }
        response.setFailureReason(d.failureReason());
        if (detail.canonicalJson() != null) {
            if (d.kind() == br.com.easyaudit.documents.domain.DocumentKind.CTE) {
                response.setCte(mapper.readValue(detail.canonicalJson(), CanonicalCte.class));
            } else {
                response.setNfe(mapper.readValue(detail.canonicalJson(), CanonicalNfe.class));
            }
        }
        return response;
    }
}

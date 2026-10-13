package br.com.easyaudit.documents.infrastructure.web;

import java.util.List;

import br.com.easyaudit.contracts.api.model.IngestionItemResult;
import br.com.easyaudit.contracts.api.model.IngestionResult;
import br.com.easyaudit.contracts.api.model.IngestionStatus;
import br.com.easyaudit.contracts.api.model.RejectionReason;
import br.com.easyaudit.documents.domain.IngestionOutcome;

final class IngestionMapper {

    private IngestionMapper() {
    }

    static IngestionResult toResult(List<IngestionOutcome> outcomes) {
        return new IngestionResult(outcomes.stream().map(IngestionMapper::toItem).toList());
    }

    private static IngestionItemResult toItem(IngestionOutcome o) {
        IngestionItemResult item = new IngestionItemResult(o.filename(), IngestionStatus.valueOf(o.status().name()));
        item.setDocumentId(o.documentId());
        if (o.reason() != null) {
            item.setReason(RejectionReason.valueOf(o.reason().name()));
        }
        item.setDetail(o.detail());
        return item;
    }
}

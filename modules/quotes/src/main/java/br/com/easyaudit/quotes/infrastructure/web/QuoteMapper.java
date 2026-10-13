package br.com.easyaudit.quotes.infrastructure.web;

import java.time.ZoneOffset;
import java.util.List;

import br.com.easyaudit.contracts.api.model.QuoteDetail;
import br.com.easyaudit.contracts.api.model.QuoteLinkStatus;
import br.com.easyaudit.contracts.api.model.QuoteReceipt;
import br.com.easyaudit.contracts.api.model.QuoteRequest;
import br.com.easyaudit.contracts.api.model.QuoteResult;
import br.com.easyaudit.contracts.api.model.QuoteRevision;
import br.com.easyaudit.contracts.api.model.QuoteSource;
import br.com.easyaudit.contracts.api.model.QuoteSummary;
import br.com.easyaudit.quotes.application.RegisterQuote;
import br.com.easyaudit.quotes.domain.StoredQuote;
import br.com.easyaudit.quotes.domain.StoredRevision;

final class QuoteMapper {

    private QuoteMapper() {
    }

    static RegisterQuote.Command toCommand(QuoteRequest r) {
        return new RegisterQuote.Command(StoredQuote.Source.valueOf(r.getSource().name()), r.getExternalId(),
                r.getCarrierCnpj(), r.getService(), r.getAmount(), r.getQuotedAt(), r.getDestinationCep(),
                r.getWeightKg(), r.getCargoValue(), r.getDeliveryDays(), r.getNfeKey(), r.getOrderReference());
    }

    static QuoteReceipt toReceipt(RegisterQuote.Receipt receipt) {
        return new QuoteReceipt(receipt.id(), QuoteResult.valueOf(receipt.result().name()), receipt.revision());
    }

    static QuoteSummary toSummary(StoredQuote q, StoredRevision current) {
        var c = current.content();
        QuoteSummary summary = new QuoteSummary(q.id(), QuoteSource.valueOf(q.source().name()), q.externalId(),
                QuoteLinkStatus.valueOf(q.linkStatus().name()), q.currentRevision(), q.carrierCnpj(), c.amount(),
                c.quotedAt().withOffsetSameInstant(ZoneOffset.UTC), q.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
        summary.setService(c.service());
        summary.setNfeKey(q.nfeKey());
        summary.setOrderReference(q.orderReference());
        summary.setLinkedNfeDocumentId(q.linkedNfeDocumentId());
        return summary;
    }

    static QuoteDetail toDetail(StoredQuote q, List<StoredRevision> revisions) {
        StoredRevision current = revisions.get(revisions.size() - 1);
        var c = current.content();
        QuoteDetail detail = new QuoteDetail(q.id(), QuoteSource.valueOf(q.source().name()), q.externalId(),
                QuoteLinkStatus.valueOf(q.linkStatus().name()), q.currentRevision(), q.carrierCnpj(), c.amount(),
                c.quotedAt().withOffsetSameInstant(ZoneOffset.UTC), q.createdAt().withOffsetSameInstant(ZoneOffset.UTC),
                revisions.stream().map(QuoteMapper::toRevision).toList());
        detail.setService(c.service());
        detail.setNfeKey(q.nfeKey());
        detail.setOrderReference(q.orderReference());
        detail.setLinkedNfeDocumentId(q.linkedNfeDocumentId());
        return detail;
    }

    private static QuoteRevision toRevision(StoredRevision r) {
        var c = r.content();
        QuoteRevision revision = new QuoteRevision(r.revision(), c.carrierCnpj(), c.amount(),
                c.quotedAt().withOffsetSameInstant(ZoneOffset.UTC), c.destinationCep(),
                r.receivedAt().withOffsetSameInstant(ZoneOffset.UTC));
        revision.setService(c.service());
        revision.setWeightKg(c.weightKg());
        revision.setCargoValue(c.cargoValue());
        revision.setDeliveryDays(c.deliveryDays());
        revision.setNfeKey(c.nfeKey());
        revision.setOrderReference(c.orderReference());
        return revision;
    }
}

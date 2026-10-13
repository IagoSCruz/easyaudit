package br.com.easyaudit.quotes.application;

import java.util.List;
import java.util.UUID;

import br.com.easyaudit.documents.api.DocumentCatalog;
import br.com.easyaudit.documents.api.DocumentCatalog.Kind;
import br.com.easyaudit.documents.api.DocumentCatalog.NfeRef;
import br.com.easyaudit.quotes.domain.StoredQuote;
import br.com.easyaudit.quotes.domain.StoredQuote.LinkMethod;
import br.com.easyaudit.quotes.domain.StoredQuote.LinkStatus;
import br.com.easyaudit.quotes.infrastructure.persistence.QuoteRepository;
import org.springframework.stereotype.Component;

/**
 * Vincula a cotação à NF-e do tenant: pela chave informada ou, sem chave, pela referência de pedido igual a um
 * {@code xPed}. Se o pedido casa com mais de uma NF-e, a cotação fica sem vínculo e marcada como
 * {@code AMBIGUOUS_ORDER_REFERENCE}. Publica {@code quote.linked} quando o vínculo é estabelecido ou muda.
 */
@Component
public class QuoteLinker {

    private final QuoteRepository quotes;
    private final DocumentCatalog documents;
    private final QuoteEventPublisher publisher;

    public QuoteLinker(QuoteRepository quotes, DocumentCatalog documents, QuoteEventPublisher publisher) {
        this.quotes = quotes;
        this.documents = documents;
        this.publisher = publisher;
    }

    /** (Re)avalia o vínculo de uma cotação com o estado atual das NF-es. */
    public void link(StoredQuote quote) {
        LinkStatus status;
        LinkMethod method = null;
        UUID target = null;
        if (quote.nfeKey() != null) {
            var nfe = documents.findProcessed(Kind.NFE, quote.nfeKey());
            if (nfe.isPresent()) {
                status = LinkStatus.LINKED;
                method = LinkMethod.KEY;
                target = nfe.get().id();
            } else {
                status = LinkStatus.UNLINKED;
            }
        } else {
            List<NfeRef> matches = documents.findProcessedNfesByOrderReference(quote.orderReference());
            if (matches.size() == 1) {
                status = LinkStatus.LINKED;
                method = LinkMethod.ORDER;
                target = matches.get(0).documentId();
            } else if (matches.size() > 1) {
                status = LinkStatus.AMBIGUOUS_ORDER_REFERENCE;
            } else {
                status = LinkStatus.UNLINKED;
            }
        }
        boolean changed = status != quote.linkStatus() || !java.util.Objects.equals(target, quote.linkedNfeDocumentId());
        if (!changed) {
            return;
        }
        quotes.setLink(quote.id(), status, method, target);
        if (status == LinkStatus.LINKED) {
            var amount = quotes.currentRevision(quote.id()).orElseThrow().content().amount();
            publisher.linked(quote.id(), target, amount);
        }
    }
}

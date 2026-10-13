package br.com.easyaudit.quotes.application;

import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.quotes.api.QuoteLookup;
import br.com.easyaudit.quotes.infrastructure.persistence.QuoteRepository;
import br.com.easyaudit.shared.Cnpj;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultQuoteLookup implements QuoteLookup {

    private final QuoteRepository quotes;

    DefaultQuoteLookup(QuoteRepository quotes) {
        this.quotes = quotes;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApplicableQuote> applicableQuote(UUID nfeDocumentId, String cteIssuerCnpj) {
        String root = Cnpj.of(cteIssuerCnpj).root();
        return quotes.applicable(nfeDocumentId, root).map(a -> new ApplicableQuote(a.quoteId(), a.revision(),
                a.amount(), a.quotedAt(), a.carrierCnpj()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> linkedNfeDocument(UUID quoteId) {
        return quotes.findById(quoteId).map(q -> q.linkedNfeDocumentId());
    }
}

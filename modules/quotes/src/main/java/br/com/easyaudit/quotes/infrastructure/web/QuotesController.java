package br.com.easyaudit.quotes.infrastructure.web;

import java.time.OffsetDateTime;
import java.util.UUID;

import br.com.easyaudit.contracts.api.QuotesApi;
import br.com.easyaudit.contracts.api.model.QuoteDetail;
import br.com.easyaudit.contracts.api.model.QuoteLinkStatus;
import br.com.easyaudit.contracts.api.model.QuotePage;
import br.com.easyaudit.contracts.api.model.QuoteReceipt;
import br.com.easyaudit.contracts.api.model.QuoteRequest;
import br.com.easyaudit.contracts.api.model.QuoteSource;
import br.com.easyaudit.contracts.api.model.PageMeta;
import br.com.easyaudit.quotes.application.QuoteQueries;
import br.com.easyaudit.quotes.application.RegisterQuote;
import br.com.easyaudit.quotes.domain.StoredQuote;
import br.com.easyaudit.quotes.infrastructure.persistence.QuoteRepository;
import br.com.easyaudit.shared.Cnpj;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QuotesController implements QuotesApi {

    private final RegisterQuote register;
    private final QuoteQueries queries;

    public QuotesController(RegisterQuote register, QuoteQueries queries) {
        this.register = register;
        this.queries = queries;
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<QuoteReceipt> registerQuote(QuoteRequest request) {
        return respond(register.register(QuoteMapper.toCommand(request)));
    }

    static ResponseEntity<QuoteReceipt> respond(RegisterQuote.Receipt receipt) {
        int status = receipt.result() == RegisterQuote.Result.CREATED ? 201 : 200;
        return ResponseEntity.status(status).body(QuoteMapper.toReceipt(receipt));
    }

    @Override
    public ResponseEntity<QuotePage> listQuotes(QuoteSource source, String carrierCnpj, QuoteLinkStatus linkStatus,
            String orderReference, String nfeKey, OffsetDateTime from, OffsetDateTime to, Integer page, Integer size) {
        String carrier = null;
        if (carrierCnpj != null && !carrierCnpj.isBlank()) {
            carrier = Cnpj.isValid(carrierCnpj) ? Cnpj.of(carrierCnpj).value() : carrierCnpj.trim();
        }
        var filter = new QuoteRepository.Filter(source == null ? null : StoredQuote.Source.valueOf(source.name()), carrier,
                linkStatus == null ? null : StoredQuote.LinkStatus.valueOf(linkStatus.name()), orderReference, nfeKey,
                from, to);
        var result = queries.search(filter, page == null ? 0 : page, size == null ? 50 : size);
        return ResponseEntity.ok(new QuotePage(result.items().stream()
                .map(r -> QuoteMapper.toSummary(r.quote(), r.current())).toList(),
                new PageMeta(result.page(), result.size(), result.totalElements(), result.totalPages())));
    }

    @Override
    public ResponseEntity<QuoteDetail> getQuote(UUID id) {
        var detail = queries.get(id);
        return ResponseEntity.ok(QuoteMapper.toDetail(detail.quote(), detail.revisions()));
    }
}

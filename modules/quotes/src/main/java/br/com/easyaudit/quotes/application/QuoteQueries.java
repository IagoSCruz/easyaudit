package br.com.easyaudit.quotes.application;

import java.util.List;
import java.util.UUID;

import br.com.easyaudit.quotes.domain.StoredQuote;
import br.com.easyaudit.quotes.domain.StoredRevision;
import br.com.easyaudit.quotes.infrastructure.persistence.QuoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consultas de cotações do tenant corrente (a RLS esconde as dos outros tenants). */
@Service
public class QuoteQueries {

    /** Cotação com a revisão vigente. */
    public record Row(StoredQuote quote, StoredRevision current) {
    }

    public record Page(List<Row> items, int page, int size, long totalElements) {
        public int totalPages() {
            return (int) Math.ceil((double) totalElements / size);
        }
    }

    public record Detail(StoredQuote quote, List<StoredRevision> revisions) {
    }

    private final QuoteRepository quotes;

    public QuoteQueries(QuoteRepository quotes) {
        this.quotes = quotes;
    }

    @Transactional(readOnly = true)
    public Page search(QuoteRepository.Filter filter, int page, int size) {
        List<Row> rows = quotes.search(filter, size, (long) page * size).stream()
                .map(q -> new Row(q, quotes.currentRevision(q.id()).orElseThrow())).toList();
        return new Page(rows, page, size, quotes.count(filter));
    }

    @Transactional(readOnly = true)
    public Detail get(UUID id) {
        StoredQuote quote = quotes.findById(id).orElseThrow(QuoteNotFoundException::new);
        return new Detail(quote, quotes.revisions(id));
    }
}

package br.com.easyaudit.quotes.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import br.com.easyaudit.quotes.domain.QuoteContent;
import br.com.easyaudit.quotes.domain.StoredQuote;
import br.com.easyaudit.quotes.domain.StoredQuote.Source;
import br.com.easyaudit.quotes.infrastructure.persistence.QuoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registro de cotações. A cotação é identificada por tenant, origem e id externo. Reenviar o mesmo conteúdo não faz
 * nada; reenviar com conteúdo diferente cria uma nova revisão, que passa a ser a vigente (a anterior fica no
 * histórico).
 */
@Service
public class RegisterQuote {

    public enum Result { CREATED, UNCHANGED, REVISED }

    /** Resultado do registro: o id da cotação, o que aconteceu e a revisão vigente. */
    public record Receipt(UUID id, Result result, int revision) {
    }

    /** Dados recebidos da API ou do webhook. */
    public record Command(Source source, String externalId, String carrierCnpj, String service, BigDecimal amount,
            OffsetDateTime quotedAt, String destinationCep, BigDecimal weightKg, BigDecimal cargoValue,
            Integer deliveryDays, String nfeKey, String orderReference) {
    }

    private final QuoteRepository quotes;
    private final QuoteLinker linker;
    private final QuoteEventPublisher publisher;

    public RegisterQuote(QuoteRepository quotes, QuoteLinker linker, QuoteEventPublisher publisher) {
        this.quotes = quotes;
        this.linker = linker;
        this.publisher = publisher;
    }

    /** @throws QuoteValidationException campos inválidos (todos juntos) */
    @Transactional
    public Receipt register(Command c) {
        QuoteContent content = QuoteValidator.validate(new QuoteValidator.Raw(c.externalId(), c.carrierCnpj(),
                c.service(), c.amount(), c.quotedAt(), c.destinationCep(), c.weightKg(), c.cargoValue(),
                c.deliveryDays(), c.nfeKey(), c.orderReference()));
        String externalId = c.externalId().trim();

        var inserted = quotes.insert(c.source(), externalId, content);
        if (inserted.isPresent()) {
            UUID id = inserted.get();
            quotes.insertRevision(id, 1, content);
            linker.link(quotes.findById(id).orElseThrow());
            return new Receipt(id, Result.CREATED, 1);
        }

        StoredQuote existing = quotes.findForUpdate(c.source(), externalId).orElseThrow();
        String currentHash = quotes.currentRevision(existing.id()).orElseThrow().contentHash();
        if (currentHash.equals(content.hash())) {
            return new Receipt(existing.id(), Result.UNCHANGED, existing.currentRevision());
        }
        int revision = existing.currentRevision() + 1;
        quotes.insertRevision(existing.id(), revision, content);
        quotes.setCurrentRevision(existing.id(), revision, content);
        publisher.revised(existing.id(), revision, content.amount());
        linker.link(quotes.findById(existing.id()).orElseThrow());
        return new Receipt(existing.id(), Result.REVISED, revision);
    }
}

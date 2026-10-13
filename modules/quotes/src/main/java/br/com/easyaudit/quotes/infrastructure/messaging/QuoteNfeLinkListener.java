package br.com.easyaudit.quotes.infrastructure.messaging;

import java.util.LinkedHashSet;
import java.util.Set;

import br.com.easyaudit.contracts.api.model.CanonicalNfe;
import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.platform.messaging.IdempotentConsumer;
import br.com.easyaudit.platform.messaging.MessagingConfiguration;
import br.com.easyaudit.quotes.application.QuoteLinker;
import br.com.easyaudit.quotes.domain.StoredQuote;
import br.com.easyaudit.quotes.infrastructure.persistence.QuoteRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumidor de {@code document.normalized} (NF-e): quando a NF-e chega depois da cotação, vincula as cotações que
 * apontam para a chave dela ou para um dos seus pedidos ({@code xPed}). O vínculo é recalculado pelo estado atual:
 * dois pedidos iguais em NF-es diferentes tornam a cotação {@code AMBIGUOUS_ORDER_REFERENCE}.
 */
@Component
class QuoteNfeLinkListener {

    private final QuoteRepository quotes;
    private final QuoteLinker linker;
    private final IdempotentConsumer idempotent;
    private final JsonMapper mapper;

    QuoteNfeLinkListener(QuoteRepository quotes, QuoteLinker linker, IdempotentConsumer idempotent,
            JsonMapper mapper) {
        this.quotes = quotes;
        this.linker = linker;
        this.idempotent = idempotent;
        this.mapper = mapper;
    }

    @RabbitListener(queues = QuotesMessagingConfiguration.LINK_NFE_QUEUE,
            containerFactory = MessagingConfiguration.LISTENER_FACTORY)
    void onDocumentNormalized(EventEnvelope envelope) {
        if (!"NFE".equals(envelope.getData().get("kind")) || envelope.getData().get("nfe") == null) {
            return;
        }
        CanonicalNfe nfe = mapper.convertValue(envelope.getData().get("nfe"), CanonicalNfe.class);
        idempotent.runOnce(QuotesMessagingConfiguration.LINK_NFE_QUEUE, envelope.getEventId(), () -> {
            Set<StoredQuote> toEvaluate = new LinkedHashSet<>(quotes.findByNfeKey(nfe.getAccessKey()));
            for (String order : nfe.getOrderReferences() == null ? Set.<String>of() : Set.copyOf(nfe.getOrderReferences())) {
                toEvaluate.addAll(quotes.findByOrderReference(order));
            }
            toEvaluate.forEach(linker::link);
        });
    }
}

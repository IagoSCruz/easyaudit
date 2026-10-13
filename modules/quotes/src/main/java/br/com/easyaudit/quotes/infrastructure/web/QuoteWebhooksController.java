package br.com.easyaudit.quotes.infrastructure.web;

import br.com.easyaudit.contracts.api.QuoteWebhooksApi;
import br.com.easyaudit.contracts.api.model.QuoteReceipt;
import br.com.easyaudit.contracts.api.model.QuoteRequest;
import br.com.easyaudit.quotes.application.RegisterQuote;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Webhook de cotações: autenticado por HMAC; o tenant é o da credencial. */
@RestController
public class QuoteWebhooksController implements QuoteWebhooksApi {

    private final RegisterQuote register;

    public QuoteWebhooksController(RegisterQuote register) {
        this.register = register;
    }

    @Override
    @PreAuthorize("hasRole('WEBHOOK')")
    public ResponseEntity<QuoteReceipt> receiveQuoteWebhook(QuoteRequest request) {
        return QuotesController.respond(register.register(QuoteMapper.toCommand(request)));
    }
}

package br.com.easyaudit.documents.infrastructure.web;

import java.util.ArrayList;
import java.util.List;

import br.com.easyaudit.contracts.api.DocumentWebhooksApi;
import br.com.easyaudit.contracts.api.model.IngestionResult;
import br.com.easyaudit.contracts.api.model.WebhookDocument;
import br.com.easyaudit.contracts.api.model.WebhookDocumentsRequest;
import br.com.easyaudit.documents.application.SubmitDocuments;
import br.com.easyaudit.documents.domain.IngestionOutcome;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.platform.web.PayloadTooLargeException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/** Webhook de documentos: autenticado por HMAC (o tenant é o da credencial), mesmas validações do upload. */
@RestController
public class DocumentWebhooksController implements DocumentWebhooksApi {

    public static final int MAX_DOCUMENTS = 100;

    private final SubmitDocuments submit;

    public DocumentWebhooksController(SubmitDocuments submit) {
        this.submit = submit;
    }

    @Override
    @PreAuthorize("hasRole('WEBHOOK')")
    public ResponseEntity<IngestionResult> receiveDocumentsWebhook(WebhookDocumentsRequest request) {
        List<WebhookDocument> documents = request.getDocuments();
        if (documents.size() > MAX_DOCUMENTS) {
            throw new PayloadTooLargeException(
                    "O envelope tem " + documents.size() + " documentos; o máximo é " + MAX_DOCUMENTS + ".");
        }
        List<IngestionOutcome> outcomes = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            WebhookDocument document = documents.get(i);
            String name = document.getName() != null ? document.getName() : "documents[" + i + "]";
            outcomes.addAll(submit.submit(Source.WEBHOOK, name, document.getContentBase64()));
        }
        return ResponseEntity.ok(IngestionMapper.toResult(outcomes));
    }
}

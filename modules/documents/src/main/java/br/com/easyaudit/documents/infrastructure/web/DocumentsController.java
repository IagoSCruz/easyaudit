package br.com.easyaudit.documents.infrastructure.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.contracts.api.DocumentsApi;
import br.com.easyaudit.contracts.api.model.DocumentKind;
import br.com.easyaudit.contracts.api.model.DocumentPage;
import br.com.easyaudit.contracts.api.model.DocumentResponse;
import br.com.easyaudit.contracts.api.model.DocumentStatus;
import br.com.easyaudit.contracts.api.model.IngestionResult;
import br.com.easyaudit.contracts.api.model.PageMeta;
import br.com.easyaudit.documents.application.DocumentQueries;
import br.com.easyaudit.documents.application.SubmitDocuments;
import br.com.easyaudit.documents.domain.ProcessingStatus;
import br.com.easyaudit.documents.infrastructure.persistence.DocumentRepository;
import br.com.easyaudit.documents.domain.IngestionOutcome;
import br.com.easyaudit.documents.domain.Source;
import java.time.OffsetDateTime;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class DocumentsController implements DocumentsApi {

    private final SubmitDocuments submit;
    private final DocumentQueries queries;
    private final JsonMapper mapper;

    public DocumentsController(SubmitDocuments submit, DocumentQueries queries, JsonMapper mapper) {
        this.submit = submit;
        this.queries = queries;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'AUDITOR', 'FINANCE')")
    public ResponseEntity<IngestionResult> uploadDocuments(List<MultipartFile> files) {
        List<IngestionOutcome> outcomes = new ArrayList<>();
        for (MultipartFile file : files) {
            String name = file.getOriginalFilename() == null ? "arquivo" : file.getOriginalFilename();
            try {
                outcomes.addAll(submit.submit(Source.UPLOAD, name, file.getBytes()));
            } catch (IOException e) {
                outcomes.add(IngestionOutcome.rejected(name,
                        br.com.easyaudit.documents.domain.RejectionReason.MALFORMED_XML, "Falha ao ler o arquivo."));
            }
        }
        return ResponseEntity.ok(IngestionMapper.toResult(outcomes));
    }

    @Override
    public ResponseEntity<DocumentPage> listDocuments(DocumentKind kind, DocumentStatus status, String accessKey,
            OffsetDateTime receivedFrom, OffsetDateTime receivedTo, Integer page, Integer size) {
        var filter = new DocumentRepository.Filter(
                kind == null ? null : br.com.easyaudit.documents.domain.DocumentKind.valueOf(kind.name()),
                status == null ? null : ProcessingStatus.valueOf(status.name()), accessKey, receivedFrom, receivedTo);
        var result = queries.search(filter, page == null ? 0 : page, size == null ? 50 : size);
        return ResponseEntity.ok(new DocumentPage(result.items().stream().map(DocumentMapper::toSummary).toList(),
                new PageMeta(result.page(), result.size(), result.totalElements(), result.totalPages())));
    }

    @Override
    public ResponseEntity<DocumentResponse> getDocument(UUID id) {
        return ResponseEntity.ok(DocumentMapper.toResponse(queries.detail(id), mapper));
    }

    @Override
    public ResponseEntity<Resource> downloadDocumentXml(UUID id) {
        var raw = queries.rawXml(id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(raw.document().accessKey() + ".xml").build().toString())
                .body(new ByteArrayResource(raw.content()));
    }
}

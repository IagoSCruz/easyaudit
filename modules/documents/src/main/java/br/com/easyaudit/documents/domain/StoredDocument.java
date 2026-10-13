package br.com.easyaudit.documents.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record StoredDocument(UUID id, DocumentKind kind, String accessKey, ProcessingStatus status,
        Lifecycle lifecycle, String failureReason, Source source, OffsetDateTime issuedAt, String rawSha256,
        OffsetDateTime receivedAt, OffsetDateTime processedAt) {
}

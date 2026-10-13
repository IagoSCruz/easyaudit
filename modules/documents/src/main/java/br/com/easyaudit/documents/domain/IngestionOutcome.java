package br.com.easyaudit.documents.domain;

import java.util.UUID;

/** Resultado da ingestão de um XML. */
public record IngestionOutcome(String filename, Status status, UUID documentId, RejectionReason reason, String detail) {

    public enum Status { ACCEPTED, DUPLICATE, REJECTED }

    public static IngestionOutcome accepted(String filename, UUID documentId) {
        return new IngestionOutcome(filename, Status.ACCEPTED, documentId, null, null);
    }

    public static IngestionOutcome duplicate(String filename, UUID existingId) {
        return new IngestionOutcome(filename, Status.DUPLICATE, existingId, null, null);
    }

    public static IngestionOutcome rejected(String filename, RejectionReason reason, String detail) {
        return new IngestionOutcome(filename, Status.REJECTED, null, reason, detail);
    }
}

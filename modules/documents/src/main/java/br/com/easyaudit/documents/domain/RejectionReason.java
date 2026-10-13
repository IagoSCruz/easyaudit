package br.com.easyaudit.documents.domain;

/** Motivo da rejeição de um XML na ingestão (espelha {@code RejectionReason} do contrato). */
public enum RejectionReason {
    UNSUPPORTED_DOCUMENT, MALFORMED_XML, FILE_TOO_LARGE, NOT_AUTHORIZED, INVALID_SIGNATURE, HOMOLOGATION_DOCUMENT,
    NOT_TENANT_DOCUMENT
}

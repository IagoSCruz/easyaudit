package br.com.easyaudit.documents.domain;

/** Tipo de XML fiscal aceito. */
public enum DocumentType {
    CTE, NFE, CTE_CANCELLATION, NFE_CANCELLATION;

    public boolean isCancellation() {
        return this == CTE_CANCELLATION || this == NFE_CANCELLATION;
    }

    public boolean isCte() {
        return this == CTE || this == CTE_CANCELLATION;
    }
}

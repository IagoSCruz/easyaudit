package br.com.easyaudit.quotes.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Consulta da cotação aplicável a um CT-e. Vale para o tenant do contexto corrente. */
public interface QuoteLookup {

    /** A cotação aplicável, com os valores da revisão vigente. */
    record ApplicableQuote(UUID quoteId, int revision, BigDecimal amount, OffsetDateTime quotedAt,
            String carrierCnpj) {
    }

    /**
     * Cotação aplicável a uma NF-e para um CT-e: a revisão vigente mais recente (pela data da cotação) vinculada à
     * NF-e cuja raiz de CNPJ da transportadora coincide com a raiz do CNPJ do emitente do CT-e (uma filial usa a
     * cotação da matriz).
     *
     * @param nfeDocumentId  documento da NF-e (de {@code DocumentCatalog})
     * @param cteIssuerCnpj  CNPJ do emitente do CT-e
     */
    Optional<ApplicableQuote> applicableQuote(UUID nfeDocumentId, String cteIssuerCnpj);

    /** Documento da NF-e a que a cotação está vinculada, se houver. */
    Optional<UUID> linkedNfeDocument(UUID quoteId);
}

package br.com.easyaudit.documents.domain;

import java.util.UUID;

/**
 * Vínculo de um documento com outro. {@code targetDocumentId} e {@code resolved} só preenchem quando o documento de
 * destino já foi processado no tenant.
 */
public record DocumentLink(UUID id, UUID fromDocumentId, LinkType type, DocumentKind targetKind, String targetKey,
        UUID targetDocumentId, boolean resolved) {

    public enum LinkType { CTE_NFE, COMPLEMENTS, SUBSTITUTES }
}

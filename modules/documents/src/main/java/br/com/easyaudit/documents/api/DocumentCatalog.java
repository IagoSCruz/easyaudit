package br.com.easyaudit.documents.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.contracts.api.model.CanonicalCte;
import br.com.easyaudit.contracts.api.model.CanonicalNfe;

/**
 * Consulta de documentos canônicos e de vínculos, para outros módulos. Vale para o tenant do contexto corrente
 * (a RLS esconde os documentos dos demais).
 */
public interface DocumentCatalog {

    enum Kind { CTE, NFE }

    enum Status { RECEIVED, PROCESSED, FAILED }

    enum Lifecycle { ACTIVE, CANCELLED, SUBSTITUTED }

    enum LinkType { CTE_NFE, COMPLEMENTS, SUBSTITUTES }

    /** Documento com o modelo canônico; {@code cte}/{@code nfe} só vêm preenchidos quando já foi processado. */
    record CanonicalDocument(UUID id, Kind kind, String accessKey, Status status, Lifecycle lifecycle,
            CanonicalCte cte, CanonicalNfe nfe) {
    }

    /** Vínculo que sai do documento; {@code targetDocumentId} só existe quando já foi resolvido. */
    record Link(LinkType type, String targetKey, boolean resolved, UUID targetDocumentId) {
    }

    /** NF-e processada e não cancelada que carrega uma referência de pedido. */
    record NfeRef(UUID documentId, String accessKey) {
    }

    Optional<CanonicalDocument> find(UUID documentId);

    /** NF-es processadas e não canceladas cujo {@code xPed} é a referência de pedido informada. */
    List<NfeRef> findProcessedNfesByOrderReference(String orderReference);

    /** Documento já processado (com modelo canônico) com o tipo e a chave de acesso. */
    Optional<CanonicalDocument> findProcessed(Kind kind, String accessKey);

    /** Vínculos que saem do documento, resolvidos e pendentes. */
    List<Link> linksFrom(UUID documentId);
}

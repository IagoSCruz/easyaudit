package br.com.easyaudit.documents.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.documents.domain.DocumentKind;
import br.com.easyaudit.documents.domain.DocumentLink;
import br.com.easyaudit.documents.domain.DocumentLink.LinkType;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentLinkRepository {

    private static final String COLUMNS =
            "id, from_document_id, link_type, target_kind, target_key, target_document_id, resolved";

    private final JdbcClient jdbc;

    public DocumentLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Cria o vínculo se ainda não existe (idempotente); {@code targetDocumentId} nulo cria pendente. */
    public void insertIfAbsent(UUID fromDocumentId, LinkType type, DocumentKind targetKind, String targetKey,
            UUID targetDocumentId) {
        jdbc.sql("""
                INSERT INTO documents.document_link
                    (id, tenant_id, from_document_id, link_type, target_kind, target_key, target_document_id,
                     resolved, resolved_at)
                VALUES (:id, :tenant, :from, :type, :kind, :key, :target, :resolved,
                        CASE WHEN :resolved THEN now() END)
                ON CONFLICT (tenant_id, from_document_id, link_type, target_key) DO NOTHING""")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value())
                .param("from", fromDocumentId).param("type", type.name()).param("kind", targetKind.name())
                .param("key", targetKey).param("target", targetDocumentId).param("resolved", targetDocumentId != null)
                .update();
    }

    /** Vínculos pendentes que apontam para o documento (tipo e chave) recém-processado. */
    public List<DocumentLink> findPendingByTarget(DocumentKind targetKind, String targetKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM documents.document_link WHERE NOT resolved"
                        + " AND target_kind = :kind AND target_key = :key ORDER BY created_at, id")
                .param("kind", targetKind.name()).param("key", targetKey).query((rs, n) -> map(rs)).list();
    }

    public void markResolved(UUID linkId, UUID targetDocumentId) {
        jdbc.sql("UPDATE documents.document_link SET resolved = true, target_document_id = :target, resolved_at = now()"
                        + " WHERE id = :id")
                .param("id", linkId).param("target", targetDocumentId).update();
    }

    /** Vínculos que saem do documento. */
    public List<DocumentLink> findFrom(UUID documentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM documents.document_link WHERE from_document_id = :id"
                        + " ORDER BY link_type, target_key")
                .param("id", documentId).query((rs, n) -> map(rs)).list();
    }

    /** Vínculo inverso de complemento/substituição: o CT-e complementar/substituto que aponta para o documento. */
    public record Inbound(DocumentLink.LinkType type, String fromAccessKey, UUID fromDocumentId) {
    }

    public List<Inbound> findInbound(UUID documentId) {
        return jdbc.sql("""
                SELECT l.link_type, d.access_key, l.from_document_id
                FROM documents.document_link l JOIN documents.document d ON d.id = l.from_document_id
                WHERE l.target_document_id = :id AND l.link_type IN ('COMPLEMENTS', 'SUBSTITUTES')
                ORDER BY l.created_at, l.id""")
                .param("id", documentId)
                .query((rs, n) -> new Inbound(DocumentLink.LinkType.valueOf(rs.getString(1)), rs.getString(2).trim(),
                        rs.getObject(3, UUID.class))).list();
    }

    public Optional<DocumentLink> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM documents.document_link WHERE id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    private static DocumentLink map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DocumentLink(rs.getObject("id", UUID.class), rs.getObject("from_document_id", UUID.class),
                LinkType.valueOf(rs.getString("link_type")), DocumentKind.valueOf(rs.getString("target_kind")),
                rs.getString("target_key").trim(), rs.getObject("target_document_id", UUID.class),
                rs.getBoolean("resolved"));
    }
}

package br.com.easyaudit.documents.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.documents.domain.DocumentKind;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Eventos de cancelamento aceitos ({@code documents.pending_cancellation}). */
@Repository
public class CancellationRepository {

    private final JdbcClient jdbc;

    public CancellationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return o id do evento novo, ou vazio se (chave, tipo, sequência) já existia no tenant */
    public Optional<UUID> insert(DocumentKind targetKind, String accessKey, String eventType, int sequence,
            String rawSha256, byte[] content) {
        return jdbc.sql("""
                INSERT INTO documents.pending_cancellation
                    (id, tenant_id, target_kind, access_key, event_type, sequence, raw_sha256, content)
                VALUES (:id, :tenant, :kind, :key, :type, :seq, :sha, :content)
                ON CONFLICT (tenant_id, access_key, event_type, sequence) DO NOTHING
                RETURNING id""")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value())
                .param("kind", targetKind.name()).param("key", accessKey).param("type", eventType)
                .param("seq", sequence).param("sha", rawSha256).param("content", content)
                .query(UUID.class).optional();
    }

    /** Evento ainda não aplicado. */
    public record PendingEvent(UUID id, DocumentKind targetKind, String accessKey) {
    }

    public Optional<PendingEvent> findPending(UUID eventId) {
        return jdbc.sql("SELECT id, target_kind, access_key FROM documents.pending_cancellation"
                        + " WHERE id = :id AND applied_at IS NULL")
                .param("id", eventId).query((rs, n) -> new PendingEvent(rs.getObject("id", UUID.class),
                        DocumentKind.valueOf(rs.getString("target_kind")), rs.getString("access_key").trim())).optional();
    }

    /** Eventos pendentes (não aplicados) que cancelam o documento com o tipo e a chave. */
    public java.util.List<PendingEvent> findPendingFor(DocumentKind targetKind, String accessKey) {
        return jdbc.sql("SELECT id, target_kind, access_key FROM documents.pending_cancellation"
                        + " WHERE target_kind = :kind AND access_key = :key AND applied_at IS NULL ORDER BY sequence")
                .param("kind", targetKind.name()).param("key", accessKey)
                .query((rs, n) -> new PendingEvent(rs.getObject("id", UUID.class),
                        DocumentKind.valueOf(rs.getString("target_kind")), rs.getString("access_key").trim())).list();
    }

    public void markApplied(UUID eventId) {
        jdbc.sql("UPDATE documents.pending_cancellation SET applied_at = now() WHERE id = :id AND applied_at IS NULL")
                .param("id", eventId).update();
    }

    public Optional<UUID> findId(String accessKey, String eventType, int sequence) {
        return jdbc.sql("""
                SELECT id FROM documents.pending_cancellation
                WHERE access_key = :key AND event_type = :type AND sequence = :seq""")
                .param("key", accessKey).param("type", eventType).param("seq", sequence)
                .query(UUID.class).optional();
    }
}

package br.com.easyaudit.documents.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.documents.domain.DocumentKind;
import br.com.easyaudit.documents.domain.Lifecycle;
import br.com.easyaudit.documents.domain.ProcessingStatus;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.domain.StoredDocument;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistência de {@code documents.document}. Toda consulta passa pela RLS do tenant corrente. */
@Repository
public class DocumentRepository {

    private static final String COLUMNS = """
            id, kind, access_key, status, lifecycle, failure_reason, source, issued_at, raw_sha256, received_at,
            processed_at""";

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Insere o documento do tenant corrente com status {@code RECEIVED}.
     *
     * @return o id novo, ou vazio se o tenant já tem um documento com a mesma chave e tipo
     */
    public Optional<UUID> insert(DocumentKind kind, String accessKey, Source source, OffsetDateTime issuedAt,
            String rawSha256) {
        UUID id = UuidV7.next();
        return jdbc.sql("""
                INSERT INTO documents.document (id, tenant_id, kind, access_key, source, issued_at, raw_sha256)
                VALUES (:id, :tenant, :kind, :key, :source, :issuedAt, :sha)
                ON CONFLICT (tenant_id, kind, access_key) DO NOTHING
                RETURNING id""")
                .param("id", id).param("tenant", TenantContext.require().value()).param("kind", kind.name())
                .param("key", accessKey).param("source", source.name()).param("issuedAt", issuedAt)
                .param("sha", rawSha256).query(UUID.class).optional();
    }

    /** Grava o modelo canônico (jsonb) e marca o documento como {@code PROCESSED}. */
    public void markProcessed(UUID id, String canonicalJson, OffsetDateTime issuedAt) {
        jdbc.sql("""
                UPDATE documents.document
                SET status = 'PROCESSED', canonical = CAST(:canonical AS jsonb), issued_at = :issuedAt,
                    failure_reason = NULL, processed_at = now(), updated_at = now()
                WHERE id = :id""")
                .param("id", id).param("canonical", canonicalJson).param("issuedAt", issuedAt).update();
    }

    /** Marca o documento como {@code FAILED}, com o motivo. */
    public void markFailed(UUID id, String reason) {
        jdbc.sql("UPDATE documents.document SET status = 'FAILED', failure_reason = :reason, updated_at = now() WHERE id = :id")
                .param("id", id).param("reason", reason).update();
    }

    /** O JSON do modelo canônico, quando o documento já foi processado. */
    public Optional<String> findCanonical(UUID id) {
        return jdbc.sql("SELECT canonical::text FROM documents.document WHERE id = :id AND canonical IS NOT NULL")
                .param("id", id).query(String.class).optional();
    }

    /** Id do documento já processado (status {@code PROCESSED}) com o tipo e a chave, no tenant corrente. */
    public Optional<UUID> findProcessedIdByKey(DocumentKind kind, String accessKey) {
        return jdbc.sql("SELECT id FROM documents.document WHERE kind = :kind AND access_key = :key AND status = 'PROCESSED'")
                .param("kind", kind.name()).param("key", accessKey).query(UUID.class).optional();
    }

    /** NF-es processadas e não canceladas com a referência de pedido ({@code orderReferences} do modelo canônico). */
    public List<StoredDocument> findProcessedNfesByOrderReference(String orderReference) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM documents.document WHERE kind = 'NFE' AND status = 'PROCESSED'"
                        + " AND lifecycle <> 'CANCELLED' AND jsonb_exists(canonical -> 'orderReferences', :ref)"
                        + " ORDER BY received_at, id")
                .param("ref", orderReference).query((rs, n) -> map(rs)).list();
    }

    public void markLifecycle(UUID id, Lifecycle lifecycle) {
        jdbc.sql("UPDATE documents.document SET lifecycle = :lifecycle, updated_at = now() WHERE id = :id")
                .param("id", id).param("lifecycle", lifecycle.name()).update();
    }

    public Optional<UUID> findIdByKey(DocumentKind kind, String accessKey) {
        return jdbc.sql("SELECT id FROM documents.document WHERE kind = :kind AND access_key = :key")
                .param("kind", kind.name()).param("key", accessKey).query(UUID.class).optional();
    }

    public Optional<StoredDocument> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM documents.document WHERE id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    /** Filtros da listagem; qualquer campo nulo é ignorado. */
    public record Filter(DocumentKind kind, ProcessingStatus status, String accessKey, OffsetDateTime receivedFrom,
            OffsetDateTime receivedTo) {
    }

    public List<StoredDocument> search(Filter filter, int limit, long offset) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM documents.document WHERE 1 = 1");
        var params = new ArrayList<Object[]>();
        where(filter, sql, params);
        sql.append(" ORDER BY received_at DESC, id DESC LIMIT :limit OFFSET :offset");
        var spec = jdbc.sql(sql.toString());
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.param("limit", limit).param("offset", offset).query((rs, n) -> map(rs)).list();
    }

    public long count(Filter filter) {
        var sql = new StringBuilder("SELECT count(*) FROM documents.document WHERE 1 = 1");
        var params = new ArrayList<Object[]>();
        where(filter, sql, params);
        var spec = jdbc.sql(sql.toString());
        for (Object[] p : params) {
            spec = spec.param((String) p[0], p[1]);
        }
        return spec.query(Long.class).single();
    }

    private static void where(Filter f, StringBuilder sql, List<Object[]> params) {
        if (f.kind() != null) {
            sql.append(" AND kind = :kind");
            params.add(new Object[] {"kind", f.kind().name()});
        }
        if (f.status() != null) {
            sql.append(" AND status = :status");
            params.add(new Object[] {"status", f.status().name()});
        }
        if (f.accessKey() != null) {
            sql.append(" AND access_key = :accessKey");
            params.add(new Object[] {"accessKey", f.accessKey()});
        }
        if (f.receivedFrom() != null) {
            sql.append(" AND received_at >= :receivedFrom");
            params.add(new Object[] {"receivedFrom", f.receivedFrom()});
        }
        if (f.receivedTo() != null) {
            sql.append(" AND received_at <= :receivedTo");
            params.add(new Object[] {"receivedTo", f.receivedTo()});
        }
    }

    private static StoredDocument map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredDocument(rs.getObject("id", UUID.class), DocumentKind.valueOf(rs.getString("kind")),
                rs.getString("access_key"), ProcessingStatus.valueOf(rs.getString("status")),
                Lifecycle.valueOf(rs.getString("lifecycle")), rs.getString("failure_reason"),
                Source.valueOf(rs.getString("source")), rs.getObject("issued_at", OffsetDateTime.class),
                rs.getString("raw_sha256"), rs.getObject("received_at", OffsetDateTime.class),
                rs.getObject("processed_at", OffsetDateTime.class));
    }
}

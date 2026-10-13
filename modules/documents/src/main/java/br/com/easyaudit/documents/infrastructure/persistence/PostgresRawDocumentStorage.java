package br.com.easyaudit.documents.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.documents.application.RawDocumentStorage;
import br.com.easyaudit.platform.tenant.TenantContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Adapter PostgreSQL da porta {@link RawDocumentStorage}. Protegido pela RLS do tenant corrente. */
@Repository
class PostgresRawDocumentStorage implements RawDocumentStorage {

    private final JdbcClient jdbc;

    PostgresRawDocumentStorage(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(UUID documentId, byte[] content) {
        jdbc.sql("""
                INSERT INTO documents.raw_document (document_id, tenant_id, content, size_bytes)
                VALUES (:id, :tenant, :content, :size)""")
                .param("id", documentId).param("tenant", TenantContext.require().value())
                .param("content", content).param("size", content.length).update();
    }

    @Override
    public Optional<byte[]> load(UUID documentId) {
        return jdbc.sql("SELECT content FROM documents.raw_document WHERE document_id = :id")
                .param("id", documentId).query(byte[].class).optional();
    }
}

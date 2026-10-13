package br.com.easyaudit.tenancy.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.shared.UuidV7;
import br.com.easyaudit.tenancy.domain.IntegrationCredential;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CredentialRepository {

    /** Credencial com o segredo cifrado; só o módulo usa, para verificar HMAC. */
    public record Stored(IntegrationCredential credential, byte[] secretCiphertext, int keyVersion) {
    }

    private static final String COLUMNS =
            "id, tenant_id, key_id, description, status, created_at, secret_ciphertext, key_version";

    private final JdbcClient jdbc;

    public CredentialRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public IntegrationCredential insert(UUID tenantId, String keyId, String description, byte[] ciphertext,
            int keyVersion) {
        UUID id = UuidV7.next();
        jdbc.sql("""
                INSERT INTO tenancy.integration_credential
                    (id, tenant_id, key_id, description, secret_ciphertext, key_version)
                VALUES (:id, :tenant, :keyId, :description, :ciphertext, :version)""")
                .param("id", id).param("tenant", tenantId).param("keyId", keyId)
                .param("description", description).param("ciphertext", ciphertext).param("version", keyVersion)
                .update();
        return findByKeyId(keyId).orElseThrow().credential();
    }

    public List<IntegrationCredential> findByTenant(UUID tenantId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenancy.integration_credential WHERE tenant_id = :tenant"
                        + " ORDER BY created_at, id")
                .param("tenant", tenantId).query((rs, n) -> map(rs)).list().stream()
                .map(Stored::credential).toList();
    }

    public Optional<Stored> findByKeyId(String keyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenancy.integration_credential WHERE key_id = :keyId")
                .param("keyId", keyId).query((rs, n) -> map(rs)).optional();
    }

    /** Revoga a credencial do tenant; @return {@code false} se ela não existe neste tenant */
    public boolean revoke(UUID tenantId, String keyId) {
        return jdbc.sql("""
                UPDATE tenancy.integration_credential SET status = 'REVOKED', revoked_at = COALESCE(revoked_at, now())
                WHERE tenant_id = :tenant AND key_id = :keyId""")
                .param("tenant", tenantId).param("keyId", keyId).update() == 1;
    }

    private static Stored map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Stored(new IntegrationCredential(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("key_id"), rs.getString("description"),
                IntegrationCredential.Status.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class)),
                rs.getBytes("secret_ciphertext"), rs.getInt("key_version"));
    }
}

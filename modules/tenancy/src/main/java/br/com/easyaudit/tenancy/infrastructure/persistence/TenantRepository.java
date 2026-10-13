package br.com.easyaudit.tenancy.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.shared.UuidV7;
import br.com.easyaudit.tenancy.domain.CnpjEntry;
import br.com.easyaudit.tenancy.domain.Tenant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TenantRepository {

    private final JdbcClient jdbc;

    public TenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertTenant(String name, String slug) {
        UUID id = UuidV7.next();
        jdbc.sql("INSERT INTO tenancy.tenant (id, name, slug) VALUES (:id, :name, :slug)")
                .param("id", id).param("name", name).param("slug", slug).update();
        return id;
    }

    /** @return o id do tenant dono da raiz depois da tentativa de registro (o próprio, se estava livre) */
    public UUID claimRoot(String root, UUID tenantId) {
        jdbc.sql("INSERT INTO tenancy.cnpj_root (root, tenant_id) VALUES (:root, :tenant) ON CONFLICT (root) DO NOTHING")
                .param("root", root).param("tenant", tenantId).update();
        return jdbc.sql("SELECT tenant_id FROM tenancy.cnpj_root WHERE root = :root")
                .param("root", root).query(UUID.class).single();
    }

    /** @return {@code false} se a entrada já existia para o tenant */
    public boolean insertCnpj(UUID tenantId, CnpjEntry entry) {
        return jdbc.sql("""
                INSERT INTO tenancy.tenant_cnpj (id, tenant_id, cnpj, kind, root)
                VALUES (:id, :tenant, :cnpj, :kind, :root)
                ON CONFLICT (tenant_id, cnpj) DO NOTHING""")
                .param("id", UuidV7.next()).param("tenant", tenantId).param("cnpj", entry.value())
                .param("kind", entry.kind().name()).param("root", entry.root()).update() == 1;
    }

    /** @return {@code true} se havia a entrada; libera a raiz quando era a última do tenant */
    public boolean deleteCnpj(UUID tenantId, String cnpj) {
        String root = jdbc.sql("DELETE FROM tenancy.tenant_cnpj WHERE tenant_id = :tenant AND cnpj = :cnpj RETURNING root")
                .param("tenant", tenantId).param("cnpj", cnpj).query(String.class).optional().orElse(null);
        if (root == null) {
            return false;
        }
        jdbc.sql("""
                DELETE FROM tenancy.cnpj_root r WHERE r.root = :root
                AND NOT EXISTS (SELECT 1 FROM tenancy.tenant_cnpj c WHERE c.root = r.root)""")
                .param("root", root).update();
        return true;
    }

    public List<CnpjEntry> cnpjs(UUID tenantId) {
        return jdbc.sql("SELECT cnpj, kind FROM tenancy.tenant_cnpj WHERE tenant_id = :tenant ORDER BY cnpj")
                .param("tenant", tenantId)
                .query((rs, n) -> new CnpjEntry(rs.getString("cnpj"), CnpjEntry.Kind.valueOf(rs.getString("kind"))))
                .list();
    }

    public Optional<Tenant> findById(UUID id) {
        return jdbc.sql("SELECT id, name, slug, created_at FROM tenancy.tenant WHERE id = :id")
                .param("id", id).query((rs, n) -> new Tenant(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("slug"), cnpjs(rs.getObject("id", UUID.class)),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .optional();
    }

    public List<Tenant> findAll() {
        return jdbc.sql("SELECT id FROM tenancy.tenant ORDER BY created_at, id")
                .query(UUID.class).list().stream().map(id -> findById(id).orElseThrow()).toList();
    }
}

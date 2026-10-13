package br.com.easyaudit.freight.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.freight.domain.StoredFreightTable;
import br.com.easyaudit.freight.domain.StoredFreightTable.Status;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FreightTableRepository {

    private static final String COLUMNS = """
            id, carrier_id, name, version, status, valid_from, valid_to, includes_icms, created_at, activated_at,
            content::text AS content""";

    private final JdbcClient jdbc;

    public FreightTableRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Próximo número de versão das tabelas da transportadora. */
    public int nextVersion(UUID carrierId) {
        return jdbc.sql("SELECT COALESCE(max(version), 0) + 1 FROM freight.freight_table WHERE carrier_id = :carrier")
                .param("carrier", carrierId).query(Integer.class).single();
    }

    public UUID insertDraft(UUID carrierId, String name, int version, LocalDate validFrom, LocalDate validTo,
            boolean includesIcms, String contentJson) {
        UUID id = UuidV7.next();
        jdbc.sql("""
                INSERT INTO freight.freight_table
                    (id, tenant_id, carrier_id, name, version, valid_from, valid_to, includes_icms, content)
                VALUES (:id, :tenant, :carrier, :name, :version, :validFrom, :validTo, :icms, CAST(:content AS jsonb))""")
                .param("id", id).param("tenant", TenantContext.require().value()).param("carrier", carrierId)
                .param("name", name).param("version", version).param("validFrom", validFrom)
                .param("validTo", validTo).param("icms", includesIcms).param("content", contentJson).update();
        return id;
    }

    public Optional<StoredFreightTable> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM freight.freight_table WHERE id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    public List<StoredFreightTable> search(UUID carrierId, Status status) {
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM freight.freight_table WHERE 1 = 1");
        if (carrierId != null) {
            sql.append(" AND carrier_id = :carrier");
        }
        if (status != null) {
            sql.append(" AND status = :status");
        }
        sql.append(" ORDER BY carrier_id, version DESC");
        var spec = jdbc.sql(sql.toString());
        if (carrierId != null) {
            spec = spec.param("carrier", carrierId);
        }
        if (status != null) {
            spec = spec.param("status", status.name());
        }
        return spec.query((rs, n) -> map(rs)).list();
    }

    /** Substitui o conteúdo de um rascunho. @return {@code false} se o id não existe ou não está em DRAFT */
    public boolean updateDraft(UUID id, String name, LocalDate validFrom, LocalDate validTo, boolean includesIcms,
            String contentJson) {
        return jdbc.sql("""
                UPDATE freight.freight_table
                SET name = :name, valid_from = :validFrom, valid_to = :validTo, includes_icms = :icms,
                    content = CAST(:content AS jsonb), updated_at = now()
                WHERE id = :id AND status = 'DRAFT'""")
                .param("id", id).param("name", name).param("validFrom", validFrom).param("validTo", validTo)
                .param("icms", includesIcms).param("content", contentJson).update() == 1;
    }

    public boolean archiveDraft(UUID id) {
        return jdbc.sql("UPDATE freight.freight_table SET status = 'ARCHIVED', archived_at = now(), updated_at = now()"
                        + " WHERE id = :id AND status = 'DRAFT'").param("id", id).update() == 1;
    }

    /** Tabelas da transportadora, travadas ({@code FOR UPDATE}) para serializar ativações concorrentes. */
    public List<StoredFreightTable> lockCarrierTables(UUID carrierId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM freight.freight_table WHERE carrier_id = :carrier"
                        + " ORDER BY version FOR UPDATE")
                .param("carrier", carrierId).query((rs, n) -> map(rs)).list();
    }

    public void activate(UUID id) {
        jdbc.sql("UPDATE freight.freight_table SET status = 'ACTIVE', activated_at = now(), updated_at = now() WHERE id = :id")
                .param("id", id).update();
    }

    /** Ajusta o fim de vigência de uma versão ativa (única mudança permitida em uma versão ACTIVE). */
    public void setValidTo(UUID id, LocalDate validTo) {
        jdbc.sql("UPDATE freight.freight_table SET valid_to = :validTo, updated_at = now() WHERE id = :id")
                .param("id", id).param("validTo", validTo).update();
    }

    /** Versões ativas da transportadora, da mais antiga para a mais nova. */
    public List<StoredFreightTable> activeOf(UUID carrierId) {
        List<StoredFreightTable> all = new ArrayList<>(jdbc.sql("SELECT " + COLUMNS
                        + " FROM freight.freight_table WHERE carrier_id = :carrier AND status = 'ACTIVE' ORDER BY valid_from, version")
                .param("carrier", carrierId).query((rs, n) -> map(rs)).list());
        return all;
    }

    private static StoredFreightTable map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StoredFreightTable(rs.getObject("id", UUID.class), rs.getObject("carrier_id", UUID.class),
                rs.getString("name"), rs.getInt("version"), Status.valueOf(rs.getString("status")),
                rs.getObject("valid_from", LocalDate.class), rs.getObject("valid_to", LocalDate.class),
                rs.getBoolean("includes_icms"), rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("activated_at", OffsetDateTime.class), rs.getString("content"));
    }
}

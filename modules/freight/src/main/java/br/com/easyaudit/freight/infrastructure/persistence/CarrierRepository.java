package br.com.easyaudit.freight.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.freight.domain.Carrier;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CarrierRepository {

    private static final String COLUMNS = "id, cnpj, name, active, auto_registered, created_at";

    private final JdbcClient jdbc;

    public CarrierRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return o id novo, ou vazio se o tenant já tem uma transportadora com o CNPJ */
    public Optional<UUID> insert(String cnpj, String name, boolean autoRegistered) {
        return jdbc.sql("""
                INSERT INTO freight.carrier (id, tenant_id, cnpj, name, auto_registered)
                VALUES (:id, :tenant, :cnpj, :name, :auto)
                ON CONFLICT (tenant_id, cnpj) DO NOTHING
                RETURNING id""")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value()).param("cnpj", cnpj)
                .param("name", name).param("auto", autoRegistered).query(UUID.class).optional();
    }

    public Optional<Carrier> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM freight.carrier WHERE id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    public Optional<Carrier> findByCnpj(String cnpj) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM freight.carrier WHERE cnpj = :cnpj")
                .param("cnpj", cnpj).query((rs, n) -> map(rs)).optional();
    }

    public List<Carrier> findAll(Boolean active) {
        String where = active == null ? "" : " WHERE active = :active";
        var spec = jdbc.sql("SELECT " + COLUMNS + " FROM freight.carrier" + where + " ORDER BY name, id");
        if (active != null) {
            spec = spec.param("active", active);
        }
        return spec.query((rs, n) -> map(rs)).list();
    }

    public void update(UUID id, String name, boolean active) {
        jdbc.sql("UPDATE freight.carrier SET name = :name, active = :active, updated_at = now() WHERE id = :id")
                .param("id", id).param("name", name).param("active", active).update();
    }

    private static Carrier map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Carrier(rs.getObject("id", UUID.class), rs.getString("cnpj").trim(), rs.getString("name"),
                rs.getBoolean("active"), rs.getBoolean("auto_registered"), rs.getObject("created_at", OffsetDateTime.class));
    }
}

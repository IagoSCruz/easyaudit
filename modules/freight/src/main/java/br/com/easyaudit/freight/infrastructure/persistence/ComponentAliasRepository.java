package br.com.easyaudit.freight.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.freight.domain.ComponentAlias;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ComponentAliasRepository {

    private static final String COLUMNS = "id, carrier_id, alias_original, component_code, created_at";

    private final JdbcClient jdbc;

    public ComponentAliasRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return o id novo, ou vazio se o nome normalizado já está mapeado para a transportadora */
    public Optional<UUID> insert(UUID carrierId, String original, String normalized, String componentCode) {
        return jdbc.sql("""
                INSERT INTO freight.component_alias
                    (id, tenant_id, carrier_id, alias_original, alias_normalized, component_code)
                VALUES (:id, :tenant, :carrier, :original, :normalized, :code)
                ON CONFLICT (tenant_id, carrier_id, alias_normalized) DO NOTHING
                RETURNING id""")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value())
                .param("carrier", carrierId).param("original", original).param("normalized", normalized)
                .param("code", componentCode).query(UUID.class).optional();
    }

    public Optional<ComponentAlias> findById(UUID carrierId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM freight.component_alias WHERE carrier_id = :carrier AND id = :id")
                .param("carrier", carrierId).param("id", id).query((rs, n) -> map(rs)).optional();
    }

    public List<ComponentAlias> findByCarrier(UUID carrierId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM freight.component_alias WHERE carrier_id = :carrier"
                        + " ORDER BY alias_normalized")
                .param("carrier", carrierId).query((rs, n) -> map(rs)).list();
    }

    /** Nome normalizado para código do componente, da transportadora com o CNPJ no tenant corrente. */
    public java.util.Map<String, String> normalizedByCarrierCnpj(String carrierCnpj) {
        var result = new java.util.LinkedHashMap<String, String>();
        jdbc.sql("""
                SELECT a.alias_normalized, a.component_code
                FROM freight.component_alias a JOIN freight.carrier c ON c.id = a.carrier_id
                WHERE c.cnpj = :cnpj
                ORDER BY a.alias_normalized""")
                .param("cnpj", carrierCnpj).query((rs, n) -> {
                    result.put(rs.getString(1), rs.getString(2));
                    return null;
                }).list();
        return result;
    }

    public boolean delete(UUID carrierId, UUID id) {
        return jdbc.sql("DELETE FROM freight.component_alias WHERE carrier_id = :carrier AND id = :id")
                .param("carrier", carrierId).param("id", id).update() == 1;
    }

    private static ComponentAlias map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ComponentAlias(rs.getObject("id", UUID.class), rs.getObject("carrier_id", UUID.class),
                rs.getString("alias_original"), rs.getString("component_code"),
                rs.getObject("created_at", OffsetDateTime.class));
    }
}

package br.com.easyaudit.audit.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.audit.domain.PolicyOverrides;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Políticas do tenant ({@code carrier_id} nulo) e das transportadoras. */
@Repository
public class PolicyRepository {

    private static final String COLUMNS = """
            absolute_tolerance, percent_tolerance, auto_approve, weight_tolerance_percent, cargo_value_tolerance_percent""";

    private final JdbcClient jdbc;

    public PolicyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Sobrescritas da política do tenant (carrierId nulo) ou da transportadora. */
    public Optional<PolicyOverrides> find(UUID carrierId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit.policy WHERE carrier_id IS NOT DISTINCT FROM :carrier")
                .param("carrier", carrierId).query((rs, n) -> new PolicyOverrides(rs.getBigDecimal(1), rs.getBigDecimal(2),
                        (Boolean) rs.getObject(3), rs.getBigDecimal(4), rs.getBigDecimal(5))).optional();
    }

    public void upsert(UUID carrierId, PolicyOverrides p) {
        jdbc.sql("""
                INSERT INTO audit.policy (id, tenant_id, carrier_id, absolute_tolerance, percent_tolerance, auto_approve,
                    weight_tolerance_percent, cargo_value_tolerance_percent)
                VALUES (:id, :tenant, :carrier, :abs, :pct, :auto, :weight, :cargo)
                ON CONFLICT ON CONSTRAINT policy_scope_unique DO UPDATE SET
                    absolute_tolerance = EXCLUDED.absolute_tolerance, percent_tolerance = EXCLUDED.percent_tolerance,
                    auto_approve = EXCLUDED.auto_approve, weight_tolerance_percent = EXCLUDED.weight_tolerance_percent,
                    cargo_value_tolerance_percent = EXCLUDED.cargo_value_tolerance_percent, updated_at = now()""")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value()).param("carrier", carrierId)
                .param("abs", p.absoluteTolerance()).param("pct", p.percentTolerance()).param("auto", p.autoApprove())
                .param("weight", p.weightTolerancePercent()).param("cargo", p.cargoValueTolerancePercent()).update();
    }

    public void delete(UUID carrierId) {
        jdbc.sql("DELETE FROM audit.policy WHERE carrier_id IS NOT DISTINCT FROM :carrier").param("carrier", carrierId).update();
    }
}

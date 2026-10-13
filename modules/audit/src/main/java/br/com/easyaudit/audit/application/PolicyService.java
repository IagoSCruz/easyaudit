package br.com.easyaudit.audit.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.application.AuditException.FieldIssue;
import br.com.easyaudit.audit.domain.AuditPolicy;
import br.com.easyaudit.audit.domain.PolicyOverrides;
import br.com.easyaudit.audit.infrastructure.persistence.PolicyRepository;
import br.com.easyaudit.freight.api.CarrierDirectory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Políticas de tolerância: a padrão do tenant (valores iniciais quando não há registro) e as sobrescritas por
 * transportadora, campo a campo.
 */
@Service
public class PolicyService {

    /** Política da transportadora: o que ela sobrescreve e a política efetiva resultante. */
    public record CarrierPolicy(PolicyOverrides overrides, AuditPolicy effective) {
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PolicyRepository policies;
    private final CarrierDirectory carriers;

    public PolicyService(PolicyRepository policies, CarrierDirectory carriers) {
        this.policies = policies;
        this.carriers = carriers;
    }

    @Transactional(readOnly = true)
    public AuditPolicy tenantPolicy() {
        return policies.find(null).map(o -> o.applyTo(AuditPolicy.DEFAULT)).orElse(AuditPolicy.DEFAULT);
    }

    /** Política efetiva para os CT-es da transportadora (nula = só a do tenant). */
    @Transactional(readOnly = true)
    public AuditPolicy effective(UUID carrierId) {
        AuditPolicy base = tenantPolicy();
        if (carrierId == null) {
            return base;
        }
        return policies.find(carrierId).map(o -> o.applyTo(base)).orElse(base);
    }

    @Transactional
    public AuditPolicy updateTenantPolicy(AuditPolicy policy) {
        validate(PolicyOverrides.of(policy));
        policies.upsert(null, PolicyOverrides.of(policy));
        return tenantPolicy();
    }

    @Transactional(readOnly = true)
    public CarrierPolicy carrierPolicy(UUID carrierId) {
        requireCarrier(carrierId);
        return new CarrierPolicy(policies.find(carrierId).orElse(PolicyOverrides.NONE), effective(carrierId));
    }

    @Transactional
    public CarrierPolicy updateCarrierPolicy(UUID carrierId, PolicyOverrides overrides) {
        requireCarrier(carrierId);
        validate(overrides);
        policies.upsert(carrierId, overrides);
        return carrierPolicy(carrierId);
    }

    @Transactional
    public void deleteCarrierPolicy(UUID carrierId) {
        requireCarrier(carrierId);
        policies.delete(carrierId);
    }

    private void requireCarrier(UUID carrierId) {
        carriers.findById(carrierId).orElseThrow(() -> AuditException.notFound("Transportadora não encontrada."));
    }

    private static void validate(PolicyOverrides p) {
        List<FieldIssue> issues = new ArrayList<>();
        if (p.absoluteTolerance() != null && p.absoluteTolerance().signum() < 0) {
            issues.add(new FieldIssue("absoluteTolerance", "A tolerância absoluta não pode ser negativa."));
        }
        percent(issues, "percentTolerance", p.percentTolerance());
        percent(issues, "weightTolerancePercent", p.weightTolerancePercent());
        percent(issues, "cargoValueTolerancePercent", p.cargoValueTolerancePercent());
        if (!issues.isEmpty()) {
            throw AuditException.invalid("VALIDATION_ERROR", "A política tem valores inválidos.", issues);
        }
    }

    private static void percent(List<FieldIssue> issues, String field, BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(HUNDRED) > 0)) {
            issues.add(new FieldIssue(field, "O percentual deve estar entre 0 e 100 (informado: " + value.stripTrailingZeros().toPlainString() + ")."));
        }
    }
}

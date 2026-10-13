package br.com.easyaudit.audit.domain;

import java.math.BigDecimal;

/** Campos que uma política substitui; os nulos herdam da política de nível acima. */
public record PolicyOverrides(BigDecimal absoluteTolerance, BigDecimal percentTolerance, Boolean autoApprove,
        BigDecimal weightTolerancePercent, BigDecimal cargoValueTolerancePercent) {

    public static final PolicyOverrides NONE = new PolicyOverrides(null, null, null, null, null);

    /** Aplica as sobrescritas sobre a base. */
    public AuditPolicy applyTo(AuditPolicy base) {
        return new AuditPolicy(absoluteTolerance != null ? absoluteTolerance : base.absoluteTolerance(),
                percentTolerance != null ? percentTolerance : base.percentTolerance(),
                autoApprove != null ? autoApprove : base.autoApprove(),
                weightTolerancePercent != null ? weightTolerancePercent : base.weightTolerancePercent(),
                cargoValueTolerancePercent != null ? cargoValueTolerancePercent : base.cargoValueTolerancePercent());
    }

    public boolean isEmpty() {
        return absoluteTolerance == null && percentTolerance == null && autoApprove == null
                && weightTolerancePercent == null && cargoValueTolerancePercent == null;
    }

    public static PolicyOverrides of(AuditPolicy policy) {
        return new PolicyOverrides(policy.absoluteTolerance(), policy.percentTolerance(), policy.autoApprove(),
                policy.weightTolerancePercent(), policy.cargoValueTolerancePercent());
    }
}

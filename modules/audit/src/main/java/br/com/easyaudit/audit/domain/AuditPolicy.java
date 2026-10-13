package br.com.easyaudit.audit.domain;

import java.math.BigDecimal;

/**
 * Política efetiva de tolerância usada na auditoria.
 *
 * @param absoluteTolerance          tolerância absoluta em R$
 * @param percentTolerance           tolerância percentual sobre o valor de referência (0 a 100)
 * @param autoApprove                aprovação automática dos casos conformes
 * @param weightTolerancePercent     tolerância de peso em % (alerta {@code WEIGHT_MISMATCH})
 * @param cargoValueTolerancePercent tolerância de valor de carga em % (alerta {@code CARGO_VALUE_MISMATCH})
 */
public record AuditPolicy(BigDecimal absoluteTolerance, BigDecimal percentTolerance, boolean autoApprove,
        BigDecimal weightTolerancePercent, BigDecimal cargoValueTolerancePercent) {

    /** Política padrão de um tenant que ainda não configurou nada. */
    public static final AuditPolicy DEFAULT = new AuditPolicy(new BigDecimal("0.00"), BigDecimal.ZERO, true,
            new BigDecimal("5"), new BigDecimal("1"));
}

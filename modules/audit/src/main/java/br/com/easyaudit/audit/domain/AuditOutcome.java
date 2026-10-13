package br.com.easyaudit.audit.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Resultado da avaliação de um CT-e pelo motor. */
public record AuditOutcome(Classification classification, BigDecimal chargedAmount, BigDecimal referenceAmount,
        ReferenceSource referenceSource, BigDecimal divergenceAmount, BigDecimal tableAmount, BigDecimal quotedAmount,
        BigDecimal underchargeAmount, List<CheckResult> checks, List<ComponentComparison> componentComparison,
        List<String> reasons) {

    public enum Classification { COMPLIANT, DIVERGENT, NOT_AUDITABLE }

    public enum ReferenceSource { TABLE, QUOTE }

    public enum Severity { BLOCKING, ALERT }

    public enum Result { PASSED, FAILED, SKIPPED }

    /** Resultado de uma verificação; {@code details} traz os valores que explicam o resultado. */
    public record CheckResult(String code, Severity severity, Result result, Map<String, Object> details) {
        public boolean failed() {
            return result == Result.FAILED;
        }
    }

    /** Valor cobrado no CT-e, valor calculado pela tabela e a diferença (cobrado − calculado) por código. */
    public record ComponentComparison(String code, BigDecimal charged, BigDecimal calculated, BigDecimal difference) {
    }

    /** Códigos das verificações bloqueantes que falharam. */
    public List<String> failedBlockingChecks() {
        return checks.stream().filter(c -> c.severity() == Severity.BLOCKING && c.failed()).map(CheckResult::code).toList();
    }

    /** Códigos dos alertas que dispararam. */
    public List<String> alerts() {
        return checks.stream().filter(c -> c.severity() == Severity.ALERT && c.failed()).map(CheckResult::code).toList();
    }

    public CheckResult check(String code) {
        return checks.stream().filter(c -> c.code().equals(code)).findFirst().orElse(null);
    }
}

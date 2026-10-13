package br.com.easyaudit.audit.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.easyaudit.audit.domain.AuditInput.Calculated;
import br.com.easyaudit.audit.domain.AuditInput.CteType;
import br.com.easyaudit.audit.domain.AuditInput.FreightMode;
import br.com.easyaudit.audit.domain.AuditInput.Nfe;
import br.com.easyaudit.audit.domain.AuditInput.NoTable;
import br.com.easyaudit.audit.domain.AuditInput.RouteNotCovered;
import br.com.easyaudit.audit.domain.AuditOutcome.CheckResult;
import br.com.easyaudit.audit.domain.AuditOutcome.Classification;
import br.com.easyaudit.audit.domain.AuditOutcome.ComponentComparison;
import br.com.easyaudit.audit.domain.AuditOutcome.ReferenceSource;
import br.com.easyaudit.audit.domain.AuditOutcome.Result;
import br.com.easyaudit.audit.domain.AuditOutcome.Severity;

/**
 * Motor de auditoria: função pura sobre um {@link AuditInput}. Compara o valor cobrado com o de referência (tabela
 * e/ou cotação) pela regra de tolerância, roda as verificações bloqueantes e de alerta e classifica o resultado.
 */
public final class AuditEngine {

    public static final String TOTAL_VS_TABLE = "TOTAL_VS_TABLE";
    public static final String TOTAL_VS_QUOTE = "TOTAL_VS_QUOTE";
    public static final String DUPLICATE_NFE = "DUPLICATE_NFE";
    public static final String COMPLEMENTARY_CHARGE = "COMPLEMENTARY_CHARGE";
    public static final String CANCELLED_NFE = "CANCELLED_NFE";
    public static final String WEIGHT_MISMATCH = "WEIGHT_MISMATCH";
    public static final String CARGO_VALUE_MISMATCH = "CARGO_VALUE_MISMATCH";
    public static final String CARRIER_MISMATCH = "CARRIER_MISMATCH";
    public static final String FOB_FREIGHT = "FOB_FREIGHT";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private AuditEngine() {
    }

    public static AuditOutcome evaluate(AuditInput in) {
        BigDecimal charged = money(in.cte().chargedAmount());
        boolean complement = in.cte().type() == CteType.COMPLEMENT;

        BigDecimal tableAmount = in.freight() instanceof Calculated c ? money(c.total()) : null;
        BigDecimal quotedAmount = in.quote() == null ? null : money(in.quote().total());

        List<CheckResult> checks = new ArrayList<>();
        checks.add(totalCheck(TOTAL_VS_TABLE, complement, charged, tableAmount, in.policy()));
        checks.add(totalCheck(TOTAL_VS_QUOTE, complement, charged, quotedAmount, in.policy()));
        checks.add(duplicateNfe(in));
        checks.add(complementaryCharge(in));
        checks.add(cancelledNfe(in));
        checks.add(weightMismatch(in));
        checks.add(cargoValueMismatch(in));
        checks.add(carrierMismatch(in));
        checks.add(fobFreight(in));

        boolean blockingFailure = checks.stream().anyMatch(c -> c.severity() == Severity.BLOCKING && c.failed());
        boolean hasReference = tableAmount != null || quotedAmount != null;
        Classification classification = blockingFailure ? Classification.DIVERGENT
                : hasReference ? Classification.COMPLIANT : Classification.NOT_AUDITABLE;

        BigDecimal reference = tableAmount != null ? tableAmount : quotedAmount;
        ReferenceSource source = tableAmount != null ? ReferenceSource.TABLE
                : quotedAmount != null ? ReferenceSource.QUOTE : null;
        BigDecimal divergence = reference == null || complement ? null : charged.subtract(reference);
        BigDecimal undercharge = reference != null && !complement && charged.compareTo(reference) < 0
                ? reference.subtract(charged) : null;

        return new AuditOutcome(classification, charged, reference, source, divergence, tableAmount, quotedAmount,
                undercharge, List.copyOf(checks), componentComparison(in), reasons(in));
    }

    // --- tolerância ---

    private static CheckResult totalCheck(String code, boolean complement, BigDecimal charged, BigDecimal reference,
            AuditPolicy policy) {
        if (complement || reference == null) {
            return new CheckResult(code, Severity.BLOCKING, Result.SKIPPED, Map.of());
        }
        BigDecimal overcharge = charged.subtract(reference);
        BigDecimal limit = limit(reference, policy);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("charged", charged);
        details.put("reference", reference);
        details.put("difference", overcharge);
        details.put("limit", limit);
        if (overcharge.signum() < 0) {
            details.put("undercharge", reference.subtract(charged));
        }
        boolean within = overcharge.compareTo(limit) <= 0;
        return new CheckResult(code, Severity.BLOCKING, within ? Result.PASSED : Result.FAILED, details);
    }

    /** O maior entre a tolerância absoluta e o percentual sobre o valor de referência. */
    static BigDecimal limit(BigDecimal reference, AuditPolicy policy) {
        BigDecimal byPercent = reference.multiply(policy.percentTolerance()).divide(HUNDRED, 10, RoundingMode.HALF_UP);
        return money(policy.absoluteTolerance().max(byPercent));
    }

    // --- verificações bloqueantes ---

    private static CheckResult duplicateNfe(AuditInput in) {
        if (in.cte().type() != CteType.NORMAL) {
            return new CheckResult(DUPLICATE_NFE, Severity.BLOCKING, Result.SKIPPED, Map.of());
        }
        String root = in.cte().carrierCnpj().substring(0, 8);
        var duplicate = in.earlierCtes().stream().filter(e -> e.valid() && e.carrierRoot().equals(root)).findFirst();
        if (duplicate.isEmpty()) {
            return new CheckResult(DUPLICATE_NFE, Severity.BLOCKING, Result.PASSED, Map.of());
        }
        var d = duplicate.get();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("nfeKey", d.nfeKey());
        details.put("otherCteKey", d.cteKey());
        details.put("otherCaseId", d.caseId());
        return new CheckResult(DUPLICATE_NFE, Severity.BLOCKING, Result.FAILED, details);
    }

    private static CheckResult complementaryCharge(AuditInput in) {
        if (in.cte().type() != CteType.COMPLEMENT) {
            return new CheckResult(COMPLEMENTARY_CHARGE, Severity.BLOCKING, Result.PASSED, Map.of());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("complementedCteKey", in.cte().relatedCteKey());
        return new CheckResult(COMPLEMENTARY_CHARGE, Severity.BLOCKING, Result.FAILED, details);
    }

    private static CheckResult cancelledNfe(AuditInput in) {
        List<String> cancelled = in.nfes().stream().filter(Nfe::cancelled).map(Nfe::key).toList();
        if (cancelled.isEmpty()) {
            return new CheckResult(CANCELLED_NFE, Severity.BLOCKING, Result.PASSED, Map.of());
        }
        return new CheckResult(CANCELLED_NFE, Severity.BLOCKING, Result.FAILED, Map.of("nfeKeys", cancelled));
    }

    // --- alertas ---

    private static CheckResult weightMismatch(AuditInput in) {
        BigDecimal cteWeight = in.cte().actualWeightKg();
        BigDecimal nfeWeight = sum(in.nfes().stream().map(Nfe::grossWeightKg).toList());
        if (cteWeight == null || in.nfes().isEmpty() || !in.pendingNfeKeys().isEmpty() || nfeWeight.signum() <= 0) {
            return new CheckResult(WEIGHT_MISMATCH, Severity.ALERT, Result.SKIPPED, Map.of());
        }
        return percentDifference(WEIGHT_MISMATCH, "cteWeightKg", cteWeight, "nfeWeightKg", nfeWeight,
                in.policy().weightTolerancePercent());
    }

    private static CheckResult cargoValueMismatch(AuditInput in) {
        BigDecimal cteValue = in.cte().cargoValue();
        BigDecimal nfeValue = sum(in.nfes().stream().map(Nfe::totalAmount).toList());
        if (cteValue == null || in.nfes().isEmpty() || !in.pendingNfeKeys().isEmpty() || nfeValue.signum() <= 0) {
            return new CheckResult(CARGO_VALUE_MISMATCH, Severity.ALERT, Result.SKIPPED, Map.of());
        }
        return percentDifference(CARGO_VALUE_MISMATCH, "cteCargoValue", cteValue, "nfeTotalAmount", nfeValue,
                in.policy().cargoValueTolerancePercent());
    }

    private static CheckResult percentDifference(String code, String leftName, BigDecimal left, String rightName,
            BigDecimal right, BigDecimal tolerancePercent) {
        BigDecimal differencePercent = left.subtract(right).abs().multiply(HUNDRED).divide(right, 4, RoundingMode.HALF_UP);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put(leftName, left);
        details.put(rightName, right);
        details.put("differencePercent", differencePercent);
        details.put("tolerancePercent", tolerancePercent);
        return new CheckResult(code, Severity.ALERT,
                differencePercent.compareTo(tolerancePercent) > 0 ? Result.FAILED : Result.PASSED, details);
    }

    private static CheckResult carrierMismatch(AuditInput in) {
        String root = in.cte().carrierCnpj().substring(0, 8);
        List<String> mismatched = in.nfes().stream()
                .filter(n -> n.carrierCnpj() != null && n.carrierCnpj().length() >= 8 && !n.carrierCnpj().startsWith(root))
                .map(Nfe::key).toList();
        if (mismatched.isEmpty()) {
            return new CheckResult(CARRIER_MISMATCH, Severity.ALERT, Result.PASSED, Map.of());
        }
        return new CheckResult(CARRIER_MISMATCH, Severity.ALERT, Result.FAILED, Map.of("nfeKeys", mismatched));
    }

    private static CheckResult fobFreight(AuditInput in) {
        List<String> fob = in.nfes().stream().filter(n -> n.freightMode() == FreightMode.FOB).map(Nfe::key).toList();
        if (fob.isEmpty() || !in.cte().takerIsTenant()) {
            return new CheckResult(FOB_FREIGHT, Severity.ALERT, Result.PASSED, Map.of());
        }
        return new CheckResult(FOB_FREIGHT, Severity.ALERT, Result.FAILED, Map.of("nfeKeys", fob));
    }

    // --- comparação por componente e motivos ---

    private static List<ComponentComparison> componentComparison(AuditInput in) {
        if (!(in.freight() instanceof Calculated calculated)) {
            return List.of();
        }
        Map<String, BigDecimal> charged = totals(in.cte().components());
        Map<String, BigDecimal> expected = totals(calculated.components());
        Set<String> codes = new LinkedHashSet<>(charged.keySet());
        codes.addAll(expected.keySet());
        List<ComponentComparison> result = new ArrayList<>();
        for (String code : codes) {
            BigDecimal c = charged.getOrDefault(code, BigDecimal.ZERO);
            BigDecimal e = expected.getOrDefault(code, BigDecimal.ZERO);
            result.add(new ComponentComparison(code, money(c), money(e), money(c.subtract(e))));
        }
        return result;
    }

    private static Map<String, BigDecimal> totals(List<AuditInput.ChargedComponent> components) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (AuditInput.ChargedComponent c : components) {
            totals.merge(c.code(), c.amount(), BigDecimal::add);
        }
        return totals;
    }

    private static List<String> reasons(AuditInput in) {
        List<String> reasons = new ArrayList<>();
        if (in.freight() instanceof NoTable) {
            reasons.add("NO_TABLE");
        } else if (in.freight() instanceof RouteNotCovered) {
            reasons.add("ROUTE_NOT_COVERED");
        }
        if (!in.pendingNfeKeys().isEmpty()) {
            reasons.add("PENDING_NFE");
        }
        return List.copyOf(reasons);
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}

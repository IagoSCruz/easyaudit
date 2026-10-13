package br.com.easyaudit.audit;

import static br.com.easyaudit.audit.domain.CaseStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import br.com.easyaudit.audit.domain.CaseStatus;
import org.junit.jupiter.api.Test;

class CaseStatusTest {

    // as classificações podem se repetir: a reauditoria pode manter o mesmo resultado
    private static final Map<CaseStatus, Set<CaseStatus>> EXPECTED = Map.of(
            PENDING_AUDIT, EnumSet.of(COMPLIANT, DIVERGENT, NOT_AUDITABLE, CANCELLED),
            COMPLIANT, EnumSet.of(COMPLIANT, DIVERGENT, NOT_AUDITABLE, APPROVED, DISPUTED, CANCELLED),
            DIVERGENT, EnumSet.of(COMPLIANT, DIVERGENT, NOT_AUDITABLE, APPROVED, DISPUTED, CANCELLED),
            NOT_AUDITABLE, EnumSet.of(COMPLIANT, DIVERGENT, NOT_AUDITABLE, APPROVED, DISPUTED, CANCELLED),
            DISPUTED, EnumSet.of(APPROVED, COMPLIANT, DIVERGENT, NOT_AUDITABLE, CANCELLED),
            APPROVED, EnumSet.of(COMPLIANT, DIVERGENT, NOT_AUDITABLE, SCHEDULED_FOR_PAYMENT, CANCELLED),
            SCHEDULED_FOR_PAYMENT, EnumSet.of(APPROVED, PAID, CANCELLED),
            PAID, EnumSet.noneOf(CaseStatus.class),
            CANCELLED, EnumSet.noneOf(CaseStatus.class));

    @Test
    void theTransitionTableIsExactlyTheSpecifiedOne() {
        for (CaseStatus from : values()) {
            for (CaseStatus to : values()) {
                assertThat(from.canMoveTo(to)).as("%s -> %s", from, to).isEqualTo(EXPECTED.get(from).contains(to));
            }
        }
    }

    @Test
    void allowedTargetsMatchTheTable() {
        for (CaseStatus from : values()) {
            assertThat(from.allowedTargets()).containsExactlyInAnyOrderElementsOf(EXPECTED.get(from));
        }
    }

    @Test
    void prohibitedSample() {
        assertThat(PAID.canMoveTo(DISPUTED)).isFalse();
        assertThat(PAID.canMoveTo(CANCELLED)).isFalse(); // PAID só recebe o alerta
        assertThat(CANCELLED.canMoveTo(APPROVED)).isFalse();
        assertThat(SCHEDULED_FOR_PAYMENT.canMoveTo(COMPLIANT)).isFalse(); // não se reabre caso em lote
        assertThat(DISPUTED.canMoveTo(DISPUTED)).isFalse();
        assertThat(PENDING_AUDIT.canMoveTo(APPROVED)).isFalse();
        assertThat(COMPLIANT.canMoveTo(PAID)).isFalse();
    }

    @Test
    void finalStatesAndGroups() {
        assertThat(List.of(PAID, CANCELLED)).allMatch(CaseStatus::isFinal);
        assertThat(Set.of(APPROVED, DISPUTED, COMPLIANT)).noneMatch(CaseStatus::isFinal);
        assertThat(CaseStatus.AUDITABLE).containsExactlyInAnyOrder(PENDING_AUDIT, COMPLIANT, DIVERGENT, NOT_AUDITABLE);
        assertThat(CaseStatus.CLASSIFIED).containsExactlyInAnyOrder(COMPLIANT, DIVERGENT, NOT_AUDITABLE);
    }
}

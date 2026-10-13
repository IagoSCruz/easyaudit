package br.com.easyaudit.audit.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estados do caso de auditoria e a tabela de transições permitidas (máquina de estados explícita, sem framework).
 * As transições automáticas (auditoria, aprovação automática, cancelamento) e as manuais (aprovar, contestar,
 * resolver, desistir, reabrir, agendar, pagar) passam todas por aqui.
 */
public enum CaseStatus {
    PENDING_AUDIT, COMPLIANT, DIVERGENT, NOT_AUDITABLE, DISPUTED, APPROVED, SCHEDULED_FOR_PAYMENT, PAID, CANCELLED;

    /** Estados em que o caso ainda pode ser (re)auditado. */
    public static final Set<CaseStatus> AUDITABLE = EnumSet.of(PENDING_AUDIT, COMPLIANT, DIVERGENT, NOT_AUDITABLE);

    /** Estados em que o auditor pode aprovar, contestar ou reauditar (resultado de auditoria já concluída). */
    public static final Set<CaseStatus> CLASSIFIED = EnumSet.of(COMPLIANT, DIVERGENT, NOT_AUDITABLE);

    private static final Map<CaseStatus, Set<CaseStatus>> ALLOWED = new EnumMap<>(CaseStatus.class);

    static {
        EnumSet<CaseStatus> classifications = EnumSet.of(COMPLIANT, DIVERGENT, NOT_AUDITABLE);
        // a auditoria leva um caso novo para o estado da classificação
        ALLOWED.put(PENDING_AUDIT, withCancelled(classifications));
        // reauditoria (classificação), aprovação, contestação e cancelamento
        for (CaseStatus from : classifications) {
            EnumSet<CaseStatus> targets = EnumSet.copyOf(classifications);
            targets.addAll(EnumSet.of(APPROVED, DISPUTED, CANCELLED));
            ALLOWED.put(from, targets);
        }
        // resolução (aprovado), desistência (volta à classificação) e cancelamento
        ALLOWED.put(DISPUTED, withCancelled(EnumSet.of(APPROVED, COMPLIANT, DIVERGENT, NOT_AUDITABLE)));
        // reabertura (volta à classificação), agendamento e cancelamento
        ALLOWED.put(APPROVED, withCancelled(EnumSet.of(COMPLIANT, DIVERGENT, NOT_AUDITABLE, SCHEDULED_FOR_PAYMENT)));
        // desagendamento, baixa e cancelamento
        ALLOWED.put(SCHEDULED_FOR_PAYMENT, withCancelled(EnumSet.of(APPROVED, PAID)));
        ALLOWED.put(PAID, EnumSet.noneOf(CaseStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(CaseStatus.class));
    }

    private static EnumSet<CaseStatus> withCancelled(EnumSet<CaseStatus> targets) {
        targets.add(CANCELLED);
        return targets;
    }

    public boolean canMoveTo(CaseStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public Set<CaseStatus> allowedTargets() {
        return Set.copyOf(ALLOWED.get(this));
    }

    public boolean isFinal() {
        return this == PAID || this == CANCELLED;
    }

    /** O estado de uma classificação de auditoria. */
    public static CaseStatus of(AuditOutcome.Classification classification) {
        return switch (classification) {
            case COMPLIANT -> COMPLIANT;
            case DIVERGENT -> DIVERGENT;
            case NOT_AUDITABLE -> NOT_AUDITABLE;
        };
    }
}

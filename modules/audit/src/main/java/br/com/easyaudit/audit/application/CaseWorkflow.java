package br.com.easyaudit.audit.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import br.com.easyaudit.audit.domain.AuditOutcome.Classification;
import br.com.easyaudit.audit.domain.CaseStatus;
import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.audit.infrastructure.persistence.CaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ações manuais sobre o caso: aprovar, contestar, resolver, desistir e reabrir, mais a aprovação em lote. Toda ação
 * trava o caso, confere a versão que o usuário viu (409 se estiver desatualizada), confere a transição na máquina de
 * estados (409 com o estado atual) e valida valores e justificativas (422). Cada transição grava o histórico
 * append-only e publica {@code audit.status-changed} na mesma transação.
 */
@Service
public class CaseWorkflow {

    public static final int MAX_BATCH = 500;

    public enum DisputeCategory { VALUE_ABOVE_REFERENCE, DUPLICATE_CHARGE, WEIGHT_DIVERGENCE, UNDUE_COMPONENT, OTHER }

    public record BatchItem(UUID caseId, long version) {
    }

    public record BatchResult(UUID caseId, boolean success, CaseStatus status, Long version, BigDecimal approvedAmount,
            String errorCode, String errorMessage) {
    }

    private final CaseRepository cases;
    private final AuditEventPublisher publisher;
    private final TransactionTemplate perItem;

    public CaseWorkflow(CaseRepository cases, AuditEventPublisher publisher,
            PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.publisher = publisher;
        this.perItem = new TransactionTemplate(transactionManager);
        this.perItem.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Aprova o caso. Sem {@code approvedAmount}, vale o valor cobrado. A justificativa é obrigatória quando o caso
     * não é {@code COMPLIANT} ou quando o valor aprovado difere do cobrado.
     */
    @Transactional
    public StoredCase approve(UUID caseId, long version, BigDecimal approvedAmount, String justification,
            String actor) {
        StoredCase c = load(caseId, version, CaseStatus.CLASSIFIED, CaseStatus.APPROVED);
        BigDecimal amount = approvedAmount == null ? c.chargedAmount() : money("approvedAmount", approvedAmount);
        requireWithin("approvedAmount", amount, c.chargedAmount(), "O valor aprovado deve estar entre zero e o cobrado.");
        boolean needsJustification = c.status() != CaseStatus.COMPLIANT || amount.compareTo(c.chargedAmount()) != 0;
        if (needsJustification && blank(justification)) {
            throw AuditException.invalid("VALIDATION_ERROR", "A justificativa é obrigatória.", List.of(
                    new AuditException.FieldIssue("justification",
                            "Obrigatória quando o caso não é COMPLIANT ou o valor aprovado difere do cobrado.")));
        }
        return move(c, CaseStatus.APPROVED, amount, "APPROVE", justification, actor);
    }

    @Transactional
    public StoredCase dispute(UUID caseId, long version, DisputeCategory category, String description,
            BigDecimal amountDue, String actor) {
        StoredCase c = load(caseId, version, CaseStatus.CLASSIFIED, CaseStatus.DISPUTED);
        List<AuditException.FieldIssue> issues = new ArrayList<>();
        if (category == null) {
            issues.add(new AuditException.FieldIssue("category", "Obrigatória."));
        }
        if (blank(description)) {
            issues.add(new AuditException.FieldIssue("description", "Obrigatória."));
        }
        BigDecimal due = null;
        if (amountDue == null || amountDue.signum() < 0) {
            issues.add(new AuditException.FieldIssue("amountDue", "Deve ser zero ou maior."));
        } else {
            due = money("amountDue", amountDue);
        }
        if (!issues.isEmpty()) {
            throw AuditException.invalid("VALIDATION_ERROR", "Contestação inválida.", issues);
        }
        cases.insertDispute(caseId, category.name(), description.trim(), due, actor);
        return move(c, CaseStatus.DISPUTED, null, "DISPUTE", category.name() + ": " + description.trim(), actor);
    }

    @Transactional
    public StoredCase resolve(UUID caseId, long version, BigDecimal agreedAmount, String note, String actor) {
        StoredCase c = load(caseId, version, Set.of(CaseStatus.DISPUTED), CaseStatus.APPROVED);
        if (agreedAmount == null) {
            throw AuditException.invalid("VALIDATION_ERROR", "O valor acordado é obrigatório.", List.of(
                    new AuditException.FieldIssue("agreedAmount", "Obrigatório.")));
        }
        BigDecimal amount = money("agreedAmount", agreedAmount);
        requireWithin("agreedAmount", amount, c.chargedAmount(), "O valor acordado deve estar entre zero e o cobrado.");
        if (blank(note)) {
            throw AuditException.invalid("VALIDATION_ERROR", "A nota é obrigatória.", List.of(
                    new AuditException.FieldIssue("note", "Obrigatória.")));
        }
        cases.closeDispute(caseId, "RESOLVED", amount, note.trim());
        return move(c, CaseStatus.APPROVED, amount, "RESOLVE", note, actor);
    }

    /** Desiste da contestação; o caso volta ao estado da última classificação. */
    @Transactional
    public StoredCase withdraw(UUID caseId, long version, String note, String actor) {
        StoredCase c = load(caseId, version, Set.of(CaseStatus.DISPUTED), null);
        cases.closeDispute(caseId, "WITHDRAWN", null, blank(note) ? null : note.trim());
        return move(c, lastClassified(c), null, "WITHDRAW", note, actor);
    }

    /** Reabre o caso aprovado que ainda não está em lote; descarta o valor aprovado. */
    @Transactional
    public StoredCase reopen(UUID caseId, long version, String reason, String actor) {
        StoredCase c = load(caseId, version, Set.of(CaseStatus.APPROVED), null);
        if (blank(reason)) {
            throw AuditException.invalid("VALIDATION_ERROR", "O motivo é obrigatório.", List.of(
                    new AuditException.FieldIssue("reason", "Obrigatório.")));
        }
        return move(c, lastClassified(c), null, "REOPEN", reason, actor);
    }

    /** Aprova vários casos pelo valor cobrado; uma transação por caso, e a falha de um não impede os outros. */
    public List<BatchResult> approveBatch(List<BatchItem> items, String justification, String actor) {
        if (items == null || items.isEmpty() || items.size() > MAX_BATCH) {
            throw AuditException.invalid("VALIDATION_ERROR", "O lote deve ter de 1 a " + MAX_BATCH + " casos.",
                    List.of(new AuditException.FieldIssue("items", "Informe de 1 a " + MAX_BATCH + " casos.")));
        }
        List<BatchResult> results = new ArrayList<>(items.size());
        for (BatchItem item : items) {
            try {
                StoredCase approved = perItem.execute(s -> approve(item.caseId(), item.version(), null, justification,
                        actor));
                results.add(new BatchResult(item.caseId(), true, approved.status(), approved.version(),
                        approved.approvedAmount(), null, null));
            } catch (AuditException e) {
                results.add(new BatchResult(item.caseId(), false, null, null, null, e.code(), e.getMessage()));
            }
        }
        return results;
    }

    // ---- internos

    /** Trava o caso e confere versão, estado de origem e a transição na máquina de estados. */
    private StoredCase load(UUID caseId, long version, Set<CaseStatus> allowedFrom, CaseStatus target) {
        StoredCase c = cases.lock(caseId).orElseThrow(() -> AuditException.notFound("Caso de auditoria não encontrado."));
        if (c.version() != version) {
            throw AuditException.conflict("VERSION_CONFLICT", "O caso foi alterado por outra pessoa (versão atual "
                    + c.version() + "). Recarregue e tente de novo.", c.status().name());
        }
        if (!allowedFrom.contains(c.status()) || (target != null && !c.status().canMoveTo(target))) {
            throw AuditException.conflict("INVALID_TRANSITION",
                    "Um caso em " + c.status() + " não aceita esta ação.", c.status().name());
        }
        return c;
    }

    private StoredCase move(StoredCase c, CaseStatus target, BigDecimal approvedAmount, String action, String note,
            String actor) {
        if (!c.status().canMoveTo(target)) {
            throw AuditException.conflict("INVALID_TRANSITION",
                    "Um caso em " + c.status() + " não pode ir para " + target + ".", c.status().name());
        }
        cases.changeStatus(c.id(), target, approvedAmount, null);
        cases.insertTransition(c.id(), c.status(), target, actor, action, blank(note) ? null : note.trim(),
                approvedAmount);
        publisher.statusChanged(c.id(), c.status(), target, actor, approvedAmount);
        return cases.findById(c.id()).orElseThrow();
    }

    private static CaseStatus lastClassified(StoredCase c) {
        Classification last = c.lastClassification();
        return last == null ? CaseStatus.NOT_AUDITABLE : CaseStatus.of(last);
    }

    private static BigDecimal money(String field, BigDecimal value) {
        if (value.scale() > 2 && value.stripTrailingZeros().scale() > 2) {
            throw AuditException.invalid("VALIDATION_ERROR", "O valor tem mais de duas casas decimais.", List.of(
                    new AuditException.FieldIssue(field, "Use no máximo duas casas decimais.")));
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static void requireWithin(String field, BigDecimal value, BigDecimal charged, String message) {
        if (value.signum() < 0 || value.compareTo(charged) > 0) {
            throw AuditException.invalid("VALIDATION_ERROR", message, List.of(new AuditException.FieldIssue(field,
                    message)));
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}

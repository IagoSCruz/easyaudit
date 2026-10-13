package br.com.easyaudit.settlement.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.easyaudit.audit.api.AuditCaseCommands;
import br.com.easyaudit.audit.api.AuditCaseCommands.CaseCommandException;
import br.com.easyaudit.audit.api.AuditCaseQueries;
import br.com.easyaudit.audit.api.AuditCaseQueries.CaseInfo;
import br.com.easyaudit.settlement.application.BatchModel.Batch;
import br.com.easyaudit.settlement.application.BatchModel.Detail;
import br.com.easyaudit.settlement.application.BatchModel.Item;
import br.com.easyaudit.settlement.application.BatchModel.Page;
import br.com.easyaudit.settlement.application.BatchModel.Status;
import br.com.easyaudit.settlement.application.SettlementException.Issue;
import br.com.easyaudit.settlement.infrastructure.persistence.BatchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lotes de pagamento: criação, manutenção, exportação, baixa manual e cancelamento. As mudanças de estado dos casos
 * (agendar, desagendar, pagar) são feitas pela {@code api} do audit na mesma transação do lote, então ou tudo
 * acontece ou nada acontece.
 */
@Service
public class PaymentBatches {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
    private static final String APPROVED = "APPROVED";

    private final BatchRepository batches;
    private final AuditCaseQueries queries;
    private final AuditCaseCommands commands;

    public PaymentBatches(BatchRepository batches, AuditCaseQueries queries, AuditCaseCommands commands) {
        this.batches = batches;
        this.queries = queries;
        this.commands = commands;
    }

    // ---- criação e manutenção

    @Transactional
    public Detail create(LocalDate dueDate, String description, List<UUID> caseIds, String actor) {
        List<Issue> issues = new ArrayList<>();
        if (dueDate == null) {
            issues.add(new Issue("dueDate", "Obrigatória."));
        }
        List<CaseInfo> infos = loadCases(caseIds, null, issues);
        if (!issues.isEmpty()) {
            throw SettlementException.invalid("INVALID_BATCH", "O lote não pôde ser criado.", issues);
        }
        CaseInfo first = infos.get(0);
        UUID batchId = batches.insert(first.carrierRoot(), first.carrierName(),
                description == null || description.isBlank() ? null : description.trim(), dueDate, actor);
        addInternal(batchId, infos, actor);
        return detail(batchId);
    }

    @Transactional
    public Detail addCases(UUID batchId, List<UUID> caseIds, String actor) {
        Batch batch = openBatch(batchId);
        List<Issue> issues = new ArrayList<>();
        List<CaseInfo> infos = loadCases(caseIds, batch.carrierRoot(), issues);
        if (!issues.isEmpty()) {
            throw SettlementException.invalid("INVALID_BATCH", "Os casos não puderam ser incluídos.", issues);
        }
        addInternal(batchId, infos, actor);
        return detail(batchId);
    }

    @Transactional
    public Detail removeCase(UUID batchId, UUID caseId, String actor) {
        openBatch(batchId);
        if (!batches.releaseItem(batchId, caseId, "REMOVED")) {
            throw SettlementException.notFound("O caso não está neste lote.");
        }
        unscheduleScheduled(List.of(caseId), actor);
        batches.recalculate(batchId);
        return detail(batchId);
    }

    @Transactional
    public Detail cancel(UUID batchId, String actor) {
        openBatch(batchId);
        List<UUID> ids = batches.activeItems(batchId).stream().map(Item::caseId).toList();
        unscheduleScheduled(ids, actor);
        batches.releaseAll(batchId, "BATCH_CANCELLED");
        batches.markCancelled(batchId);
        return detail(batchId);
    }

    // ---- baixa manual

    @Transactional
    public Detail registerPayment(UUID batchId, LocalDate paidOn, String reference, String actor) {
        Batch batch = openBatch(batchId);
        List<Issue> issues = new ArrayList<>();
        if (paidOn == null) {
            issues.add(new Issue("paidOn", "Obrigatória."));
        } else if (paidOn.isAfter(LocalDate.now(SAO_PAULO))) {
            issues.add(new Issue("paidOn", "A data do pagamento não pode ser futura."));
        }
        if (reference == null || reference.isBlank()) {
            issues.add(new Issue("reference", "Obrigatória."));
        }
        if (batch.caseCount() == 0) {
            issues.add(new Issue("batch", "O lote não tem casos."));
        }
        if (!issues.isEmpty()) {
            throw SettlementException.invalid("INVALID_PAYMENT", "A baixa não pôde ser registrada.", issues);
        }
        List<UUID> ids = batches.activeItems(batchId).stream().map(Item::caseId).toList();
        try {
            commands.markPaid(ids, actor);
        } catch (CaseCommandException e) {
            throw stateConflict(e);
        }
        batches.markPaid(batchId, paidOn, reference.trim(), actor);
        return detail(batchId);
    }

    // ---- exportação

    /** CSV do lote; registra a exportação (data e usuário). */
    @Transactional
    public byte[] export(UUID batchId, String actor) {
        Batch batch = batches.find(batchId).orElseThrow(() -> SettlementException.notFound("Lote não encontrado."));
        List<Item> items = batches.displayItems(batchId);
        batches.insertExport(batchId, actor, items.size());
        return BatchCsv.render(batch, items);
    }

    // ---- consultas

    @Transactional(readOnly = true)
    public Detail get(UUID batchId) {
        return detail(batchId);
    }

    @Transactional(readOnly = true)
    public Page list(Status status, String carrierRoot, LocalDate dueFrom, LocalDate dueTo, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw SettlementException.invalid("VALIDATION_ERROR", "Página ou tamanho inválido (tamanho de 1 a 100).",
                    "size");
        }
        String root = carrierRoot == null || carrierRoot.isBlank() ? null : carrierRoot.trim();
        return new Page(batches.list(status, root, dueFrom, dueTo, size, (long) page * size), page, size,
                batches.count(status, root, dueFrom, dueTo));
    }

    // ---- retirada automática (caso cancelado)

    /** O caso foi cancelado: sai do lote {@code OPEN} em que estiver e os totais são recalculados. */
    @Transactional
    public void onCaseCancelled(UUID caseId) {
        batches.openBatchOfCase(caseId).ifPresent(batchId -> {
            batches.lock(batchId);
            if (batches.releaseItem(batchId, caseId, "CASE_CANCELLED")) {
                batches.recalculate(batchId);
            }
        });
    }

    // ---- internos

    private Detail detail(UUID batchId) {
        Batch batch = batches.find(batchId).orElseThrow(() -> SettlementException.notFound("Lote não encontrado."));
        return new Detail(batch, batches.displayItems(batchId), batches.exports(batchId));
    }

    private Batch openBatch(UUID batchId) {
        Batch batch = batches.lock(batchId).orElseThrow(() -> SettlementException.notFound("Lote não encontrado."));
        if (batch.status() != Status.OPEN) {
            throw SettlementException.conflict("BATCH_NOT_OPEN", "O lote está " + batch.status() + " e não pode ser alterado.");
        }
        return batch;
    }

    private void addInternal(UUID batchId, List<CaseInfo> infos, String actor) {
        for (CaseInfo c : infos) {
            batches.insertItem(batchId, c.id(), c.cteAccessKey(), c.carrierCnpj(), c.carrierName(), c.emissionDate(),
                    c.chargedAmount(), c.approvedAmount());
        }
        try {
            commands.schedule(infos.stream().map(CaseInfo::id).toList(), batchId, actor);
        } catch (CaseCommandException e) {
            throw stateConflict(e);
        }
        batches.recalculate(batchId);
    }

    /** Desagenda os casos que ainda estão agendados (um caso cancelado no meio já saiu do estado). */
    private void unscheduleScheduled(List<UUID> caseIds, String actor) {
        List<UUID> scheduled = queries.findAll(caseIds).stream()
                .filter(c -> "SCHEDULED_FOR_PAYMENT".equals(c.status())).map(CaseInfo::id).toList();
        if (!scheduled.isEmpty()) {
            try {
                commands.unschedule(scheduled, actor);
            } catch (CaseCommandException e) {
                throw stateConflict(e);
            }
        }
    }

    /**
     * Valida os casos de uma lista: devem existir, estar {@code APPROVED} e ser da mesma raiz de transportadora
     * ({@code expectedRoot}, ou a do primeiro caso quando for nulo). Acumula todos os problemas.
     */
    private List<CaseInfo> loadCases(List<UUID> caseIds, String expectedRoot, List<Issue> issues) {
        if (caseIds == null || caseIds.isEmpty()) {
            issues.add(new Issue("caseIds", "Informe ao menos um caso."));
            return List.of();
        }
        Map<UUID, CaseInfo> byId = new LinkedHashMap<>();
        queries.findAll(caseIds).forEach(c -> byId.put(c.id(), c));
        List<CaseInfo> valid = new ArrayList<>();
        String root = expectedRoot;
        for (UUID id : caseIds.stream().distinct().toList()) {
            CaseInfo c = byId.get(id);
            if (c == null) {
                issues.add(new Issue("caseIds[" + id + "]", "Caso não encontrado."));
                continue;
            }
            if (root == null) {
                root = c.carrierRoot();
            }
            if (!APPROVED.equals(c.status())) {
                issues.add(new Issue("caseIds[" + id + "]", "O caso está " + c.status() + "; só casos APPROVED entram em lote."));
            } else if (!root.equals(c.carrierRoot())) {
                issues.add(new Issue("caseIds[" + id + "]", "O caso é de outra transportadora (raiz " + c.carrierRoot()
                        + "; o lote é da raiz " + root + ")."));
            } else {
                valid.add(c);
            }
        }
        return valid;
    }

    private static SettlementException stateConflict(CaseCommandException e) {
        return SettlementException.conflict(e.hasCancelled() ? "CASE_CANCELLED" : "CASE_STATE_CHANGED",
                (e.hasCancelled() ? "Algum caso foi cancelado. " : "Algum caso mudou de estado. ")
                        + "Recarregue o lote e tente de novo.");
    }
}

package br.com.easyaudit.audit.application;

import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import br.com.easyaudit.documents.api.DocumentCatalog;
import br.com.easyaudit.audit.application.AuditInputAssembler.Assembled;
import br.com.easyaudit.audit.domain.AuditEngine;
import br.com.easyaudit.audit.domain.AuditInput.CteType;
import br.com.easyaudit.audit.domain.AuditOutcome;
import br.com.easyaudit.audit.domain.AuditOutcome.Classification;
import br.com.easyaudit.audit.domain.CaseStatus;
import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.audit.infrastructure.persistence.CaseRepository;
import br.com.easyaudit.contracts.api.model.CanonicalCte;
import br.com.easyaudit.shared.Cnpj;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Casos de auditoria: cria o caso de cada CT-e normalizado (exatamente um, mesmo com notificação repetida) e executa
 * as auditorias. Cada execução é uma transação: trava as NF-es (ordem crescente) para a verificação de duplicidade,
 * monta o snapshot, roda o motor, grava a revisão imutável, aplica o estado, a aprovação automática e publica os
 * eventos.
 */
@Service
public class AuditCases {

    public static final String SYSTEM = "SYSTEM";
    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    /** O que motivou a execução da auditoria (gravado na revisão). */
    public enum Trigger { INITIAL, LINK_RESOLVED, QUOTE_CHANGED, TABLE_ACTIVATED, MANUAL }

    private final CaseRepository cases;
    private final AuditInputAssembler assembler;
    private final AuditEventPublisher publisher;
    private final JsonMapper mapper;
    private final DocumentCatalog documents;
    private final CaseCancellation cancellation;

    public AuditCases(CaseRepository cases, AuditInputAssembler assembler, AuditEventPublisher publisher,
            JsonMapper mapper, DocumentCatalog documents, CaseCancellation cancellation) {
        this.documents = documents;
        this.cancellation = cancellation;
        this.cases = cases;
        this.assembler = assembler;
        this.publisher = publisher;
        this.mapper = mapper;
    }

    /**
     * Cria o caso do CT-e normalizado e faz a primeira auditoria. Se o caso já existe (notificação repetida), não
     * faz nada.
     *
     * @return o caso, ou vazio se ele já existia
     */
    @Transactional
    public java.util.Optional<StoredCase> createAndAudit(UUID cteDocumentId) {
        CanonicalCte cte = assembler.cteOf(cteDocumentId);
        var id = cases.insertIfAbsent(cteDocumentId, cte.getAccessKey(), CteType.valueOf(cte.getType().name()),
                cte.getCarrier().getCnpj(), cte.getTotalAmount());
        if (id.isEmpty()) {
            return java.util.Optional.empty();
        }
        StoredCase audited = runAudit(cases.lock(id.get()).orElseThrow(), Trigger.INITIAL);
        // o CT-e pode ter sido cancelado ou substituído antes de o caso existir: o caso já nasce cancelado
        var lifecycle = documents.find(cteDocumentId).map(DocumentCatalog.CanonicalDocument::lifecycle).orElse(null);
        if (lifecycle == DocumentCatalog.Lifecycle.CANCELLED || lifecycle == DocumentCatalog.Lifecycle.SUBSTITUTED) {
            String reason = lifecycle == DocumentCatalog.Lifecycle.SUBSTITUTED ? CaseCancellation.CTE_SUBSTITUTED
                    : CaseCancellation.CTE_CANCELLED;
            return cancellation.cancelByCte(cteDocumentId, reason);
        }
        return java.util.Optional.of(audited);
    }

    /**
     * Reaudita o caso. Casos fora dos estados auditáveis não são reauditados: na reauditoria automática
     * ({@code manual = false}) são ignorados; na manual, geram conflito (409) com o estado atual.
     *
     * @return o caso depois da reauditoria, ou vazio se foi ignorado
     */
    @Transactional
    public java.util.Optional<StoredCase> reaudit(UUID caseId, Trigger trigger, boolean manual) {
        StoredCase locked = cases.lock(caseId).orElse(null);
        if (locked == null) {
            if (manual) {
                throw AuditException.notFound("Caso de auditoria não encontrado.");
            }
            return java.util.Optional.empty();
        }
        if (!CaseStatus.AUDITABLE.contains(locked.status())) {
            if (manual) {
                throw AuditException.conflict("INVALID_TRANSITION", "Um caso em " + locked.status()
                        + " não pode ser reauditado.", locked.status().name());
            }
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(runAudit(locked, trigger));
    }

    private StoredCase runAudit(StoredCase locked, Trigger trigger) {
        UUID caseId = locked.id();
        CanonicalCte keysSource = assembler.cteOf(locked.cteDocumentId());
        List<String> keys = keysSource.getReferencedNfeKeys() == null ? List.of() : keysSource.getReferencedNfeKeys();
        // duas auditorias concorrentes da mesma NF-e são serializadas: a segunda enxerga a primeira
        cases.lockNfeKeys(keys);

        Assembled assembled = assembler.assemble(caseId, locked.cteDocumentId());
        AuditOutcome outcome = AuditEngine.evaluate(assembled.input());
        CanonicalCte cte = assembled.cte();

        cases.replaceCaseNfes(caseId, locked.cteType(), Cnpj.of(cte.getCarrier().getCnpj()).root(), keys);

        int revision = locked.currentRevision() + 1;
        UUID revisionId = cases.insertRevision(caseId, revision, trigger.name(), outcome.classification(),
                outcome.chargedAmount(), outcome.referenceAmount(), snapshot(trigger, assembled, outcome));

        CaseStatus classified = CaseStatus.of(outcome.classification());
        cases.applyAudit(caseId, classified, outcome.classification(),
                assembled.carrier() == null ? null : assembled.carrier().id(),
                assembled.carrier() == null ? null : assembled.carrier().name(),
                cte.getIssuedAt().atZoneSameInstant(SAO_PAULO).toLocalDate(),
                cte.getDestination() == null ? null : cte.getDestination().getUf(), outcome.chargedAmount(),
                outcome.referenceAmount(), outcome.divergenceAmount(), outcome.failedBlockingChecks(), outcome.alerts(),
                revision);
        publisher.completed(caseId, revisionId, outcome.classification(), outcome.chargedAmount(),
                outcome.referenceAmount(), outcome.failedBlockingChecks());

        if (classified != locked.status()) {
            cases.insertTransition(caseId, locked.status(), classified, SYSTEM, "AUDIT", null, null);
            publisher.statusChanged(caseId, locked.status(), classified, SYSTEM, null);
        }
        if (outcome.classification() == Classification.COMPLIANT && assembled.input().policy().autoApprove()) {
            cases.changeStatus(caseId, CaseStatus.APPROVED, outcome.chargedAmount(), null);
            cases.insertTransition(caseId, CaseStatus.COMPLIANT, CaseStatus.APPROVED, SYSTEM, "AUTO_APPROVE",
                    "Aprovação automática pela política", outcome.chargedAmount());
            publisher.statusChanged(caseId, CaseStatus.COMPLIANT, CaseStatus.APPROVED, SYSTEM, outcome.chargedAmount());
        }
        return cases.findById(caseId).orElseThrow();
    }

    private String snapshot(Trigger trigger, Assembled assembled, AuditOutcome outcome) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("trigger", trigger.name());
        snapshot.put("input", assembled.input());
        snapshot.put("outcome", outcome);
        return mapper.writeValueAsString(snapshot);
    }
}

package br.com.easyaudit.audit.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.infrastructure.persistence.CaseRepository;
import br.com.easyaudit.documents.api.DocumentCatalog;
import br.com.easyaudit.freight.api.CarrierDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reauditoria automática. Reage a vínculos de NF-e resolvidos, a mudanças de cotação e à ativação de tabelas. Só casos
 * em {@code PENDING_AUDIT}, {@code COMPLIANT}, {@code DIVERGENT} ou {@code NOT_AUDITABLE} são reauditados; os demais
 * são ignorados.
 */
@Service
public class Reaudits {

    private static final Logger log = LoggerFactory.getLogger(Reaudits.class);
    static final int PAGE_SIZE = 200;

    private final CaseRepository cases;
    private final AuditCases auditCases;
    private final DocumentCatalog documents;
    private final CarrierDirectory carriers;
    private final TransactionTemplate perCase;
    private final TransactionTemplate readOnly;

    public Reaudits(CaseRepository cases, AuditCases auditCases, DocumentCatalog documents, CarrierDirectory carriers,
            PlatformTransactionManager transactionManager) {
        this.cases = cases;
        this.auditCases = auditCases;
        this.documents = documents;
        this.carriers = carriers;
        this.perCase = new TransactionTemplate(transactionManager);
        this.perCase.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    /** O vínculo de NF-e do CT-e foi resolvido: reaudita o caso do CT-e, se houver. */
    public void onLinkResolved(UUID cteDocumentId) {
        perCase.executeWithoutResult(s -> cases.findByCteDocument(cteDocumentId)
                .ifPresent(c -> auditCases.reaudit(c.id(), AuditCases.Trigger.LINK_RESOLVED, false)));
    }

    /** A cotação aplicável de uma NF-e mudou: reaudita os casos dos CT-es que a referenciam. */
    public void onQuoteChanged(UUID nfeDocumentId) {
        var nfe = documents.find(nfeDocumentId);
        if (nfe.isEmpty()) {
            return;
        }
        // as consultas precisam de transação para que o tenant seja aplicado (RLS)
        for (UUID caseId : readOnly.execute(s -> cases.auditableCasesReferencing(nfe.get().accessKey()))) {
            perCase.executeWithoutResult(s -> auditCases.reaudit(caseId, AuditCases.Trigger.QUOTE_CHANGED, false));
        }
    }

    /**
     * Uma tabela da transportadora foi ativada com vigência {@code [validFrom, validTo]}: reaudita, em páginas de 200
     * casos e uma transação por caso, os casos auditáveis dela emitidos nesse período.
     *
     * @param eventTime instante do evento; casos que já têm revisão posterior a ele não são refeitos
     * @return quantos casos foram reauditados
     */
    public int onTableActivated(UUID carrierId, LocalDate validFrom, LocalDate validTo, OffsetDateTime eventTime) {
        var carrier = carriers.findById(carrierId);
        if (carrier.isEmpty()) {
            return 0;
        }
        int total = 0;
        UUID after = null;
        while (true) {
            UUID cursor = after;
            List<UUID> page = readOnly.execute(s -> cases.auditablePageOfCarrier(carrier.get().cnpj(), validFrom,
                    validTo, eventTime, cursor, PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            for (UUID caseId : page) {
                try {
                    perCase.executeWithoutResult(s ->
                            auditCases.reaudit(caseId, AuditCases.Trigger.TABLE_ACTIVATED, false));
                    total++;
                } catch (RuntimeException e) {
                    // uma falha não derruba a reauditoria dos demais casos; a mensagem vai para o retry/DLQ no fim
                    log.warn("Falha ao reauditar o caso {} depois da ativação da tabela", caseId, e);
                    throw e;
                }
            }
            after = page.get(page.size() - 1);
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        return total;
    }
}

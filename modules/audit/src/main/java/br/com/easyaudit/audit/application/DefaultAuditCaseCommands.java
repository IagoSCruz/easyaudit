package br.com.easyaudit.audit.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.api.AuditCaseCommands;
import br.com.easyaudit.audit.domain.CaseStatus;
import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.audit.infrastructure.persistence.CaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultAuditCaseCommands implements AuditCaseCommands {

    private final CaseRepository cases;
    private final AuditEventPublisher publisher;

    DefaultAuditCaseCommands(CaseRepository cases, AuditEventPublisher publisher) {
        this.cases = cases;
        this.publisher = publisher;
    }

    @Override
    @Transactional
    public void schedule(List<UUID> caseIds, UUID batchId, String actor) {
        for (StoredCase c : lockAll(caseIds, CaseStatus.APPROVED, CaseStatus.SCHEDULED_FOR_PAYMENT)) {
            cases.setBatch(c.id(), batchId);
            transition(c, CaseStatus.SCHEDULED_FOR_PAYMENT, "SCHEDULE", actor);
        }
    }

    @Override
    @Transactional
    public void unschedule(List<UUID> caseIds, String actor) {
        for (StoredCase c : lockAll(caseIds, CaseStatus.SCHEDULED_FOR_PAYMENT, CaseStatus.APPROVED)) {
            cases.setBatch(c.id(), null);
            transition(c, CaseStatus.APPROVED, "UNSCHEDULE", actor);
        }
    }

    @Override
    @Transactional
    public void markPaid(List<UUID> caseIds, String actor) {
        for (StoredCase c : lockAll(caseIds, CaseStatus.SCHEDULED_FOR_PAYMENT, CaseStatus.PAID)) {
            transition(c, CaseStatus.PAID, "PAY", actor);
        }
    }

    private void transition(StoredCase c, CaseStatus target, String action, String actor) {
        cases.changeStatus(c.id(), target, c.approvedAmount(), null);
        cases.insertTransition(c.id(), c.status(), target, actor, action, null, c.approvedAmount());
        publisher.statusChanged(c.id(), c.status(), target, actor, c.approvedAmount());
    }

    /** Trava os casos em ordem de id (evita deadlock) e exige o estado esperado em todos. */
    private List<StoredCase> lockAll(List<UUID> caseIds, CaseStatus expected, CaseStatus target) {
        List<StoredCase> locked = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (UUID id : caseIds.stream().distinct().sorted().toList()) {
            var found = cases.lock(id);
            if (found.isEmpty()) {
                rejected.add(new Rejected(id, null));
            } else if (found.get().status() != expected || !found.get().status().canMoveTo(target)) {
                rejected.add(new Rejected(id, found.get().status().name()));
            } else {
                locked.add(found.get());
            }
        }
        if (!rejected.isEmpty()) {
            throw new CaseCommandException("Casos fora do estado " + expected + ": " + rejected.size(), rejected);
        }
        return locked;
    }
}

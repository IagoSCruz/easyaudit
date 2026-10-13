package br.com.easyaudit.audit.application;

import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.audit.domain.CaseStatus;
import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.audit.infrastructure.persistence.CaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cancelamento do caso quando o CT-e é cancelado ou substituído. O caso vai para {@code CANCELLED} a partir de
 * qualquer estado, exceto {@code PAID}: este continua {@code PAID} e recebe o alerta {@code CANCELLED_AFTER_PAYMENT}.
 */
@Service
public class CaseCancellation {

    public static final String CTE_CANCELLED = "CTE_CANCELLED";
    public static final String CTE_SUBSTITUTED = "CTE_SUBSTITUTED";
    public static final String CANCELLED_AFTER_PAYMENT = "CANCELLED_AFTER_PAYMENT";

    private static final String SYSTEM = "SYSTEM";

    private final CaseRepository cases;
    private final AuditEventPublisher publisher;

    public CaseCancellation(CaseRepository cases, AuditEventPublisher publisher) {
        this.cases = cases;
        this.publisher = publisher;
    }

    /** @param reason {@code CTE_CANCELLED} ou {@code CTE_SUBSTITUTED} @return o caso, ou vazio se ainda não existe */
    @Transactional
    public Optional<StoredCase> cancelByCte(UUID cteDocumentId, String reason) {
        StoredCase found = cases.findByCteDocument(cteDocumentId).orElse(null);
        if (found == null) {
            return Optional.empty();
        }
        StoredCase c = cases.lock(found.id()).orElseThrow();
        if (c.status() == CaseStatus.CANCELLED) {
            return Optional.of(c);
        }
        if (c.status() == CaseStatus.PAID) {
            cases.addAlert(c.id(), CANCELLED_AFTER_PAYMENT);
            return cases.findById(c.id());
        }
        cases.changeStatus(c.id(), CaseStatus.CANCELLED, c.approvedAmount(), reason);
        cases.insertTransition(c.id(), c.status(), CaseStatus.CANCELLED, SYSTEM, "CANCEL", reason, c.approvedAmount());
        publisher.statusChanged(c.id(), c.status(), CaseStatus.CANCELLED, SYSTEM, c.approvedAmount());
        return cases.findById(c.id());
    }

    public static String reasonOf(String documentReason) {
        return "SUBSTITUTED".equals(documentReason) ? CTE_SUBSTITUTED : CTE_CANCELLED;
    }
}

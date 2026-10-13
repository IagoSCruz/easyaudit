package br.com.easyaudit.audit.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.api.AuditCaseQueries;
import br.com.easyaudit.audit.infrastructure.persistence.CaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultAuditCaseQueries implements AuditCaseQueries {

    private final CaseRepository cases;

    DefaultAuditCaseQueries(CaseRepository cases) {
        this.cases = cases;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CaseInfo> findAll(Collection<UUID> caseIds) {
        return cases.findAllByIds(caseIds).stream().map(c -> new CaseInfo(c.id(), c.status().name(),
                c.cteDocumentId(), c.cteAccessKey(), c.carrierCnpj(), c.carrierName(), c.emissionDate(),
                c.chargedAmount(), c.approvedAmount())).toList();
    }
}

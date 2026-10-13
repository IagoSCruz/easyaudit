package br.com.easyaudit.audit.infrastructure.web;

import java.time.ZoneOffset;

import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.contracts.api.model.AuditCaseSummary;
import br.com.easyaudit.contracts.api.model.CaseStatus;

final class AuditCaseMapper {

    private AuditCaseMapper() {
    }

    static AuditCaseSummary toSummary(StoredCase c) {
        AuditCaseSummary summary = new AuditCaseSummary(c.id(), c.cteDocumentId(), c.cteAccessKey(),
                CaseStatus.valueOf(c.status().name()), c.version(), c.carrierCnpj(), c.chargedAmount(),
                c.currentRevision(), c.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
        summary.setCteNumber(c.cteAccessKey().substring(25, 34));
        summary.setCteSeries(c.cteAccessKey().substring(22, 25));
        summary.setCarrierId(c.carrierId());
        summary.setCarrierName(c.carrierName());
        summary.setEmissionDate(c.emissionDate());
        summary.setDestinationUf(c.destinationUf());
        summary.setReferenceAmount(c.referenceAmount());
        summary.setDivergenceAmount(c.divergenceAmount());
        summary.setFailedChecks(c.failedChecks());
        summary.setAlerts(c.alerts());
        summary.setApprovedAmount(c.approvedAmount());
        return summary;
    }
}

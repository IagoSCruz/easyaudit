package br.com.easyaudit.audit.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.domain.AuditInput.CteType;

/** Caso de auditoria como gravado (colunas desnormalizadas incluídas). */
public record StoredCase(UUID id, UUID cteDocumentId, String cteAccessKey, CteType cteType, CaseStatus status,
        AuditOutcome.Classification lastClassification, long version, UUID carrierId, String carrierCnpj,
        String carrierName, LocalDate emissionDate, String destinationUf, BigDecimal chargedAmount,
        BigDecimal referenceAmount, BigDecimal divergenceAmount, List<String> failedChecks, List<String> alerts,
        BigDecimal approvedAmount, String cancelReason, int currentRevision, UUID batchId, OffsetDateTime createdAt) {
}

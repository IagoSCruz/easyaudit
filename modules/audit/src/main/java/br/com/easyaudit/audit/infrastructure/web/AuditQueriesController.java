package br.com.easyaudit.audit.infrastructure.web;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.application.CaseQueries;
import br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository.Filter;
import br.com.easyaudit.contracts.api.AuditDashboardApi;
import br.com.easyaudit.contracts.api.AuditQueriesApi;
import br.com.easyaudit.contracts.api.model.AuditCaseDetail;
import br.com.easyaudit.contracts.api.model.AuditCasePage;
import br.com.easyaudit.contracts.api.model.AuditRevisionDetail;
import br.com.easyaudit.contracts.api.model.AuditRevisionSummary;
import br.com.easyaudit.contracts.api.model.CarrierRanking;
import br.com.easyaudit.contracts.api.model.CarrierRankingRow;
import br.com.easyaudit.contracts.api.model.CaseStatus;
import br.com.easyaudit.contracts.api.model.CaseTransition;
import br.com.easyaudit.contracts.api.model.Classification;
import br.com.easyaudit.contracts.api.model.DashboardSummary;
import br.com.easyaudit.contracts.api.model.DisputeCategory;
import br.com.easyaudit.contracts.api.model.DisputeInfo;
import br.com.easyaudit.contracts.api.model.PageMeta;
import br.com.easyaudit.contracts.api.model.StatusTotals;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditQueriesController implements AuditQueriesApi, AuditDashboardApi {

    private final CaseQueries queries;

    public AuditQueriesController(CaseQueries queries) {
        this.queries = queries;
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AuditCasePage> listAudits(List<CaseStatus> status, UUID carrierId, LocalDate from, LocalDate to,
            String destinationUf, String failedCheck, java.math.BigDecimal minDivergence, String alert, String q,
            String sort, String direction, Integer page, Integer size) {
        Filter filter = filter(status, carrierId, from, to, destinationUf, failedCheck, minDivergence, alert, q);
        var result = queries.list(filter, sort, direction, page == null ? 0 : page, size == null ? 20 : size);
        return ResponseEntity.ok(new AuditCasePage(result.items().stream().map(AuditCaseMapper::toSummary).toList(),
                new PageMeta(result.page(), result.size(), result.totalElements(), result.totalPages())));
    }

    static Filter filter(List<CaseStatus> status, UUID carrierId, LocalDate from, LocalDate to, String destinationUf,
            String failedCheck, java.math.BigDecimal minDivergence, String alert, String q) {
        return new Filter(status == null ? List.of() : status.stream().map(CaseStatus::name).toList(), carrierId, from,
                to, destinationUf, failedCheck, minDivergence, alert, q);
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AuditCaseDetail> getAudit(UUID id) {
        var d = queries.detail(id);
        AuditCaseDetail detail = new AuditCaseDetail(AuditCaseMapper.toSummary(d.caseRow()), d.linkedNfes(),
                d.pendingNfeKeys(), d.previousRevisions().stream().map(r -> {
                    AuditRevisionSummary s = new AuditRevisionSummary(r.revision(),
                            AuditRevisionSummary.TriggerEnum.valueOf(r.trigger()), Classification.valueOf(r.classification()),
                            r.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
                    s.setChargedAmount(r.chargedAmount());
                    s.setReferenceAmount(r.referenceAmount());
                    return s;
                }).toList(), d.history().stream().map(t -> {
                    CaseTransition tr = new CaseTransition(CaseStatus.valueOf(t.fromStatus()), CaseStatus.valueOf(t.toStatus()),
                            t.actor(), t.action(), t.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
                    tr.setNote(t.note());
                    tr.setApprovedAmount(t.approvedAmount());
                    return tr;
                }).toList());
        detail.setCte(d.cte());
        detail.setQuote(d.quote());
        detail.setBatchId(d.caseRow().batchId());
        if (d.currentRevision() != null) {
            var r = d.currentRevision().revision();
            AuditRevisionDetail current = new AuditRevisionDetail(r.revision(),
                    AuditRevisionDetail.TriggerEnum.valueOf(r.trigger()), Classification.valueOf(r.classification()),
                    r.createdAt().withOffsetSameInstant(ZoneOffset.UTC), d.currentRevision().snapshot());
            current.setChargedAmount(r.chargedAmount());
            current.setReferenceAmount(r.referenceAmount());
            detail.setCurrentRevision(current);
        }
        if (d.dispute() != null) {
            var x = d.dispute();
            DisputeInfo dispute = new DisputeInfo(DisputeCategory.valueOf(x.category()), x.description(), x.amountDue(),
                    DisputeInfo.StatusEnum.valueOf(x.status()), x.createdBy(), x.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
            dispute.setAgreedAmount(x.agreedAmount());
            dispute.setResolutionNote(x.resolutionNote());
            dispute.setRevision(x.revision());
            dispute.setResolvedAt(x.resolvedAt() == null ? null : x.resolvedAt().withOffsetSameInstant(ZoneOffset.UTC));
            detail.setDispute(dispute);
        }
        return ResponseEntity.ok(detail);
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<DashboardSummary> getDashboardSummary(LocalDate from, LocalDate to, UUID carrierId) {
        var s = queries.summary(from, to, carrierId);
        var t = s.totals();
        DashboardSummary summary = new DashboardSummary(
                s.byStatus().entrySet().stream().map(e -> new StatusTotals(CaseStatus.valueOf(e.getKey().name()),
                        e.getValue().count(), e.getValue().charged())).toList(),
                t.cases(), t.charged(), t.reference(), t.divergence(), t.approved(), s.savings());
        summary.setConformanceRate(s.conformanceRate());
        return ResponseEntity.ok(summary);
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CarrierRanking> getDashboardCarriers(LocalDate from, LocalDate to) {
        return ResponseEntity.ok(new CarrierRanking(queries.ranking(from, to).stream().map(r -> {
            var row = r.row();
            CarrierRankingRow out = new CarrierRankingRow(row.carrierCnpj(), row.cases(), row.charged(),
                    row.divergentCount(), row.divergence(), r.savings());
            out.setCarrierId(row.carrierId());
            out.setCarrierName(row.carrierName());
            out.setDivergenceRate(r.divergenceRate());
            return out;
        }).toList()));
    }
}

package br.com.easyaudit.audit.infrastructure.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.application.CaseWorkflow;
import br.com.easyaudit.audit.application.CaseWorkflow.BatchItem;
import br.com.easyaudit.contracts.api.AuditActionsApi;
import br.com.easyaudit.contracts.api.model.ApproveCaseRequest;
import br.com.easyaudit.contracts.api.model.AuditCaseSummary;
import br.com.easyaudit.contracts.api.model.BatchApproveItemResult;
import br.com.easyaudit.contracts.api.model.BatchApproveRequest;
import br.com.easyaudit.contracts.api.model.BatchApproveResponse;
import br.com.easyaudit.contracts.api.model.CaseStatus;
import br.com.easyaudit.contracts.api.model.DisputeCaseRequest;
import br.com.easyaudit.contracts.api.model.ReopenCaseRequest;
import br.com.easyaudit.contracts.api.model.ResolveDisputeRequest;
import br.com.easyaudit.contracts.api.model.WithdrawDisputeRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditActionsController implements AuditActionsApi {

    private final CaseWorkflow workflow;

    public AuditActionsController(CaseWorkflow workflow) {
        this.workflow = workflow;
    }

    @Override
    @PreAuthorize("hasRole('AUDITOR')")
    public ResponseEntity<AuditCaseSummary> approveCase(UUID id, ApproveCaseRequest request) {
        return ok(workflow.approve(id, request.getVersion(), request.getApprovedAmount(), request.getJustification(),
                actor()));
    }

    @Override
    @PreAuthorize("hasRole('AUDITOR')")
    public ResponseEntity<BatchApproveResponse> approveCasesBatch(BatchApproveRequest request) {
        List<BatchItem> items = request.getItems().stream()
                .map(i -> new BatchItem(i.getCaseId(), i.getVersion())).toList();
        var results = workflow.approveBatch(items, request.getJustification(), actor());
        int succeeded = (int) results.stream().filter(CaseWorkflow.BatchResult::success).count();
        BatchApproveResponse response = new BatchApproveResponse(results.stream().map(r -> {
            BatchApproveItemResult item = new BatchApproveItemResult(r.caseId(), r.success());
            if (r.status() != null) {
                item.setStatus(CaseStatus.valueOf(r.status().name()));
            }
            item.setVersion(r.version());
            item.setApprovedAmount(r.approvedAmount());
            item.setErrorCode(r.errorCode());
            item.setErrorMessage(r.errorMessage());
            return item;
        }).toList(), succeeded, results.size() - succeeded);
        return ResponseEntity.ok(response);
    }

    @Override
    @PreAuthorize("hasRole('AUDITOR')")
    public ResponseEntity<AuditCaseSummary> disputeCase(UUID id, DisputeCaseRequest request) {
        return ok(workflow.dispute(id, request.getVersion(),
                CaseWorkflow.DisputeCategory.valueOf(request.getCategory().name()), request.getDescription(),
                request.getAmountDue(), actor()));
    }

    @Override
    @PreAuthorize("hasRole('AUDITOR')")
    public ResponseEntity<AuditCaseSummary> resolveDispute(UUID id, ResolveDisputeRequest request) {
        return ok(workflow.resolve(id, request.getVersion(), request.getAgreedAmount(), request.getNote(), actor()));
    }

    @Override
    @PreAuthorize("hasRole('AUDITOR')")
    public ResponseEntity<AuditCaseSummary> withdrawDispute(UUID id, WithdrawDisputeRequest request) {
        return ok(workflow.withdraw(id, request.getVersion(), request.getNote(), actor()));
    }

    @Override
    @PreAuthorize("hasRole('AUDITOR')")
    public ResponseEntity<AuditCaseSummary> reopenCase(UUID id, ReopenCaseRequest request) {
        return ok(workflow.reopen(id, request.getVersion(), request.getReason(), actor()));
    }

    private static ResponseEntity<AuditCaseSummary> ok(br.com.easyaudit.audit.domain.StoredCase c) {
        return ResponseEntity.ok(AuditCaseMapper.toSummary(c));
    }

    /** Quem agiu: o usuário do token (nome de usuário ou subject). */
    static String actor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt) {
            String username = jwt.getToken().getClaimAsString("preferred_username");
            return username != null ? username : jwt.getToken().getSubject();
        }
        return authentication == null ? "SYSTEM" : authentication.getName();
    }
}

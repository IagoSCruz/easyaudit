package br.com.easyaudit.audit.infrastructure.web;

import java.util.UUID;

import br.com.easyaudit.audit.application.AuditCases;
import br.com.easyaudit.contracts.api.AuditReauditApi;
import br.com.easyaudit.contracts.api.model.AuditCaseSummary;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditReauditController implements AuditReauditApi {

    private final AuditCases cases;

    public AuditReauditController(AuditCases cases) {
        this.cases = cases;
    }

    @Override
    @PreAuthorize("hasRole('AUDITOR')")
    public ResponseEntity<AuditCaseSummary> reauditCase(UUID id) {
        var reaudited = cases.reaudit(id, AuditCases.Trigger.MANUAL, true).orElseThrow();
        return ResponseEntity.ok(AuditCaseMapper.toSummary(reaudited));
    }
}

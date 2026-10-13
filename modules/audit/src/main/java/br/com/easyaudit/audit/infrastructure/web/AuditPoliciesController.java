package br.com.easyaudit.audit.infrastructure.web;

import java.util.UUID;

import br.com.easyaudit.audit.application.PolicyService;
import br.com.easyaudit.audit.domain.AuditPolicy;
import br.com.easyaudit.audit.domain.PolicyOverrides;
import br.com.easyaudit.contracts.api.AuditPoliciesApi;
import br.com.easyaudit.contracts.api.model.AuditPolicyOverrides;
import br.com.easyaudit.contracts.api.model.CarrierAuditPolicy;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditPoliciesController implements AuditPoliciesApi {

    private final PolicyService service;

    public AuditPoliciesController(PolicyService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<br.com.easyaudit.contracts.api.model.AuditPolicy> getDefaultAuditPolicy() {
        return ResponseEntity.ok(toApi(service.tenantPolicy()));
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<br.com.easyaudit.contracts.api.model.AuditPolicy> updateDefaultAuditPolicy(
            br.com.easyaudit.contracts.api.model.AuditPolicy policy) {
        return ResponseEntity.ok(toApi(service.updateTenantPolicy(new AuditPolicy(policy.getAbsoluteTolerance(),
                policy.getPercentTolerance(), policy.getAutoApprove(), policy.getWeightTolerancePercent(),
                policy.getCargoValueTolerancePercent()))));
    }

    @Override
    public ResponseEntity<CarrierAuditPolicy> getCarrierAuditPolicy(UUID id) {
        return ResponseEntity.ok(toApi(service.carrierPolicy(id)));
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<CarrierAuditPolicy> updateCarrierAuditPolicy(UUID id, AuditPolicyOverrides overrides) {
        return ResponseEntity.ok(toApi(service.updateCarrierPolicy(id, new PolicyOverrides(overrides.getAbsoluteTolerance(),
                overrides.getPercentTolerance(), overrides.getAutoApprove(), overrides.getWeightTolerancePercent(),
                overrides.getCargoValueTolerancePercent()))));
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteCarrierAuditPolicy(UUID id) {
        service.deleteCarrierPolicy(id);
        return ResponseEntity.noContent().build();
    }

    private static br.com.easyaudit.contracts.api.model.AuditPolicy toApi(AuditPolicy p) {
        return new br.com.easyaudit.contracts.api.model.AuditPolicy(p.absoluteTolerance(), p.percentTolerance(),
                p.autoApprove(), p.weightTolerancePercent(), p.cargoValueTolerancePercent());
    }

    private static CarrierAuditPolicy toApi(PolicyService.CarrierPolicy cp) {
        AuditPolicyOverrides overrides = new AuditPolicyOverrides();
        overrides.setAbsoluteTolerance(cp.overrides().absoluteTolerance());
        overrides.setPercentTolerance(cp.overrides().percentTolerance());
        overrides.setAutoApprove(cp.overrides().autoApprove());
        overrides.setWeightTolerancePercent(cp.overrides().weightTolerancePercent());
        overrides.setCargoValueTolerancePercent(cp.overrides().cargoValueTolerancePercent());
        return new CarrierAuditPolicy(overrides, toApi(cp.effective()));
    }
}

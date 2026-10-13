package br.com.easyaudit.tenancy.infrastructure.web;

import java.util.List;

import br.com.easyaudit.contracts.api.TenantCnpjsApi;
import br.com.easyaudit.contracts.api.model.AddTenantCnpjRequest;
import br.com.easyaudit.contracts.api.model.TenantCnpjResponse;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.tenancy.application.TenantService;
import br.com.easyaudit.tenancy.domain.CnpjEntry;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantCnpjsController implements TenantCnpjsApi {

    private final TenantService service;

    public TenantCnpjsController(TenantService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<List<TenantCnpjResponse>> listTenantCnpjs() {
        return ResponseEntity.ok(service.listCnpjs(TenantContext.require().value()).stream()
                .map(TenantCnpjsController::toResponse).toList());
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<TenantCnpjResponse> addTenantCnpj(AddTenantCnpjRequest request) {
        CnpjEntry entry = service.addCnpj(TenantContext.require().value(), request.getCnpj());
        return ResponseEntity.status(201).body(toResponse(entry));
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> removeTenantCnpj(String cnpj) {
        service.removeCnpj(TenantContext.require().value(), cnpj);
        return ResponseEntity.noContent().build();
    }

    private static TenantCnpjResponse toResponse(CnpjEntry entry) {
        return new TenantCnpjResponse(entry.value(), TenantCnpjResponse.KindEnum.valueOf(entry.kind().name()));
    }
}

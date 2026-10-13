package br.com.easyaudit.tenancy.infrastructure.web;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.contracts.api.TenantsApi;
import br.com.easyaudit.contracts.api.model.CreateTenantRequest;
import br.com.easyaudit.contracts.api.model.TenantResponse;
import br.com.easyaudit.tenancy.application.TenantService;
import br.com.easyaudit.tenancy.domain.CnpjEntry;
import br.com.easyaudit.tenancy.domain.Tenant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TenantsController implements TenantsApi {

    private final TenantService service;

    public TenantsController(TenantService service) {
        this.service = service;
    }

    @Override
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantResponse> createTenant(CreateTenantRequest request) {
        Tenant tenant = service.create(request.getName(), request.getSlug(), request.getCnpjs());
        return ResponseEntity.status(201).body(toResponse(tenant));
    }

    @Override
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<List<TenantResponse>> listTenants() {
        return ResponseEntity.ok(service.list().stream().map(TenantsController::toResponse).toList());
    }

    @Override
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public ResponseEntity<TenantResponse> getTenant(UUID id) {
        return ResponseEntity.ok(toResponse(service.get(id)));
    }

    static TenantResponse toResponse(Tenant tenant) {
        return new TenantResponse(tenant.id(), tenant.name(), tenant.slug(),
                tenant.cnpjs().stream().map(CnpjEntry::value).toList(),
                tenant.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
    }
}

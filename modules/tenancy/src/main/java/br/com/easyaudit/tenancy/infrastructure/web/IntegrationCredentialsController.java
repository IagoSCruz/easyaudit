package br.com.easyaudit.tenancy.infrastructure.web;

import java.time.ZoneOffset;
import java.util.List;

import br.com.easyaudit.contracts.api.IntegrationCredentialsApi;
import br.com.easyaudit.contracts.api.model.CreateIntegrationCredentialRequest;
import br.com.easyaudit.contracts.api.model.CreatedIntegrationCredentialResponse;
import br.com.easyaudit.contracts.api.model.CredentialStatus;
import br.com.easyaudit.contracts.api.model.IntegrationCredentialResponse;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.tenancy.application.CredentialService;
import br.com.easyaudit.tenancy.domain.IntegrationCredential;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class IntegrationCredentialsController implements IntegrationCredentialsApi {

    private final CredentialService service;

    public IntegrationCredentialsController(CredentialService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<CreatedIntegrationCredentialResponse> createIntegrationCredential(
            CreateIntegrationCredentialRequest request) {
        var created = service.create(TenantContext.require().value(), request.getDescription());
        IntegrationCredential c = created.credential();
        return ResponseEntity.status(201).body(new CreatedIntegrationCredentialResponse(c.keyId(), created.secret(),
                c.description(), c.createdAt().withOffsetSameInstant(ZoneOffset.UTC), status(c)));
    }

    @Override
    public ResponseEntity<List<IntegrationCredentialResponse>> listIntegrationCredentials() {
        return ResponseEntity.ok(service.list(TenantContext.require().value()).stream()
                .map(IntegrationCredentialsController::toResponse).toList());
    }

    @Override
    public ResponseEntity<IntegrationCredentialResponse> revokeIntegrationCredential(String keyId) {
        return ResponseEntity.ok(toResponse(service.revoke(TenantContext.require().value(), keyId)));
    }

    private static IntegrationCredentialResponse toResponse(IntegrationCredential c) {
        return new IntegrationCredentialResponse(c.keyId(), c.description(),
                c.createdAt().withOffsetSameInstant(ZoneOffset.UTC), status(c));
    }

    private static CredentialStatus status(IntegrationCredential c) {
        return CredentialStatus.valueOf(c.status().name());
    }
}

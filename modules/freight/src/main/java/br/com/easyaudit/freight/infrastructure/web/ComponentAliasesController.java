package br.com.easyaudit.freight.infrastructure.web;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.contracts.api.ComponentAliasesApi;
import br.com.easyaudit.contracts.api.model.ComponentAlias;
import br.com.easyaudit.contracts.api.model.ComponentCode;
import br.com.easyaudit.contracts.api.model.CreateComponentAliasRequest;
import br.com.easyaudit.freight.application.ComponentAliasService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ComponentAliasesController implements ComponentAliasesApi {

    private final ComponentAliasService service;

    public ComponentAliasesController(ComponentAliasService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<List<ComponentAlias>> listComponentAliases(UUID id) {
        return ResponseEntity.ok(service.list(id).stream().map(ComponentAliasesController::toResponse).toList());
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<ComponentAlias> createComponentAlias(UUID id, CreateComponentAliasRequest request) {
        return ResponseEntity.status(201).body(toResponse(
                service.create(id, request.getAlias(), request.getComponentCode().name())));
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Void> deleteComponentAlias(UUID id, UUID aliasId) {
        service.delete(id, aliasId);
        return ResponseEntity.noContent().build();
    }

    private static ComponentAlias toResponse(br.com.easyaudit.freight.domain.ComponentAlias a) {
        return new ComponentAlias(a.id(), a.alias(), ComponentCode.valueOf(a.componentCode()),
                a.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
    }
}

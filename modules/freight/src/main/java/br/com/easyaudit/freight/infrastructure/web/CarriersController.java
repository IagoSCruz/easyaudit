package br.com.easyaudit.freight.infrastructure.web;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.contracts.api.CarriersApi;
import br.com.easyaudit.contracts.api.model.Carrier;
import br.com.easyaudit.contracts.api.model.CreateCarrierRequest;
import br.com.easyaudit.contracts.api.model.UpdateCarrierRequest;
import br.com.easyaudit.freight.application.CarrierService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CarriersController implements CarriersApi {

    private final CarrierService service;

    public CarriersController(CarrierService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<List<Carrier>> listCarriers(Boolean active) {
        return ResponseEntity.ok(service.list(active).stream().map(CarriersController::toResponse).toList());
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Carrier> createCarrier(CreateCarrierRequest request) {
        return ResponseEntity.status(201).body(toResponse(service.create(request.getCnpj(), request.getName())));
    }

    @Override
    public ResponseEntity<Carrier> getCarrier(UUID id) {
        return ResponseEntity.ok(toResponse(service.get(id)));
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<Carrier> updateCarrier(UUID id, UpdateCarrierRequest request) {
        return ResponseEntity.ok(toResponse(service.update(id, request.getName(), request.getActive())));
    }

    static Carrier toResponse(br.com.easyaudit.freight.domain.Carrier c) {
        return new Carrier(c.id(), c.cnpj(), c.name(), c.active(), c.autoRegistered(),
                c.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
    }
}

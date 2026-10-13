package br.com.easyaudit.freight.infrastructure.web;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.contracts.api.FreightTablesApi;
import br.com.easyaudit.contracts.api.model.CalculatedComponent;
import br.com.easyaudit.contracts.api.model.CalculationOutcome;
import br.com.easyaudit.contracts.api.model.CalculationResult;
import br.com.easyaudit.contracts.api.model.CreateFreightTableRequest;
import br.com.easyaudit.contracts.api.model.FreightTable;
import br.com.easyaudit.contracts.api.model.FreightTableStatus;
import br.com.easyaudit.contracts.api.model.FreightTableSummary;
import br.com.easyaudit.contracts.api.model.ShipmentInput;
import br.com.easyaudit.contracts.api.model.UpdateFreightTableRequest;
import br.com.easyaudit.freight.application.FreightTableService;
import br.com.easyaudit.freight.domain.StoredFreightTable;
import br.com.easyaudit.freight.infrastructure.xlsx.FreightTableXlsx;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class FreightTablesController implements FreightTablesApi {

    private final FreightTableService service;

    public FreightTablesController(FreightTableService service) {
        this.service = service;
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<FreightTable> createFreightTable(CreateFreightTableRequest request) {
        var created = service.create(request.getCarrierId(), request.getContent(), FreightTableService.JSON_LOCATION);
        return ResponseEntity.status(201).body(toResponse(created));
    }

    @Override
    public ResponseEntity<List<FreightTableSummary>> listFreightTables(UUID carrierId, FreightTableStatus status) {
        var domainStatus = status == null ? null : StoredFreightTable.Status.valueOf(status.name());
        return ResponseEntity.ok(service.list(carrierId, domainStatus).stream().map(this::toSummary).toList());
    }

    @Override
    public ResponseEntity<FreightTable> getFreightTable(UUID id) {
        return ResponseEntity.ok(toResponse(service.get(id)));
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<FreightTable> importFreightTable(UUID carrierId, MultipartFile file) {
        try {
            return ResponseEntity.status(201).body(toResponse(service.importXlsx(carrierId, file.getBytes())));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Falha ao ler o arquivo enviado", e);
        }
    }

    @Override
    public ResponseEntity<Resource> downloadFreightTableTemplate() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("modelo-tabela-de-frete.xlsx").build().toString())
                .body(new ByteArrayResource(FreightTableXlsx.template()));
    }

    // implementados nas próximas tasks
    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<FreightTable> updateFreightTable(UUID id, UpdateFreightTableRequest request) {
        return ResponseEntity.ok(toResponse(service.update(id, request.getContent())));
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<FreightTable> archiveFreightTable(UUID id) {
        return ResponseEntity.ok(toResponse(service.archive(id)));
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<FreightTable> newFreightTableVersion(UUID id) {
        return ResponseEntity.status(201).body(toResponse(service.newVersion(id)));
    }

    @Override
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<FreightTable> activateFreightTable(UUID id) {
        return ResponseEntity.ok(toResponse(service.activate(id)));
    }

    @Override
    public ResponseEntity<CalculationResult> simulateFreightTable(UUID id, ShipmentInput input) {
        var shipment = new br.com.easyaudit.freight.domain.ShipmentInput(input.getDestinationCep(),
                input.getOriginCep(), input.getActualWeightKg(), input.getCubedWeightKg(), input.getVolumeM3(),
                input.getCargoValue(), input.getIcmsRatePercent());
        return ResponseEntity.ok(toApi(service.simulate(id, shipment)));
    }

    private static CalculationResult toApi(br.com.easyaudit.freight.domain.CalculationResult result) {
        return switch (result) {
            case br.com.easyaudit.freight.domain.CalculationResult.RouteNotCovered r ->
                    new CalculationResult(CalculationOutcome.ROUTE_NOT_COVERED, r.tableId(), r.tableVersion());
            case br.com.easyaudit.freight.domain.CalculationResult.Calculated c -> {
                CalculationResult out = new CalculationResult(CalculationOutcome.CALCULATED, c.tableId(), c.tableVersion());
                out.setRouteName(c.routeName());
                out.setWeightBandFromKg(c.bandFromKg());
                out.setWeightBandToKg(c.bandToKg());
                out.setActualWeightKg(c.actualWeightKg());
                out.setCubedWeightKg(c.cubedWeightKg());
                out.setChargeableWeightKg(c.chargeableWeightKg());
                out.setComponents(c.components().stream().map(x -> new CalculatedComponent(x.code(), x.amount())).toList());
                out.setSubtotal(c.subtotal());
                out.setIcmsAdded(c.icmsAdded());
                out.setTotal(c.total());
                yield out;
            }
        };
    }

    private FreightTableSummary toSummary(StoredFreightTable t) {
        FreightTableSummary summary = new FreightTableSummary(t.id(), t.carrierId(), t.name(), t.version(),
                FreightTableStatus.valueOf(t.status().name()), t.validFrom(), t.includesIcms(),
                t.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
        summary.setValidTo(t.validTo());
        if (t.activatedAt() != null) {
            summary.setActivatedAt(t.activatedAt().withOffsetSameInstant(ZoneOffset.UTC));
        }
        return summary;
    }

    private FreightTable toResponse(StoredFreightTable t) {
        FreightTable table = new FreightTable(t.id(), t.carrierId(), t.name(), t.version(),
                FreightTableStatus.valueOf(t.status().name()), t.validFrom(), t.includesIcms(),
                t.createdAt().withOffsetSameInstant(ZoneOffset.UTC), service.contentOf(t));
        table.setValidTo(t.validTo());
        if (t.activatedAt() != null) {
            table.setActivatedAt(t.activatedAt().withOffsetSameInstant(ZoneOffset.UTC));
        }
        return table;
    }
}

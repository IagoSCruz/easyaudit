package br.com.easyaudit.freight.application;

import java.time.OffsetDateTime;
import java.util.UUID;

import br.com.easyaudit.freight.api.FreightCalculation;
import br.com.easyaudit.freight.domain.CalculationResult;
import br.com.easyaudit.freight.domain.FreightCalculator;
import br.com.easyaudit.freight.domain.InvalidShipmentException;
import br.com.easyaudit.freight.domain.ShipmentInput;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FreightCalculationService implements FreightCalculation {

    private final FreightTableSelection selection;

    public FreightCalculationService(FreightTableSelection selection) {
        this.selection = selection;
    }

    @Override
    @Transactional(readOnly = true)
    public Outcome calculate(UUID carrierId, OffsetDateTime issuedAt, Shipment shipment) {
        ShipmentInput input = toInput(shipment);
        try {
            FreightCalculator.validate(input); // antes da seleção, para rejeitar entradas ruins mesmo sem tabela
            return switch (selection.selectTable(carrierId, issuedAt, shipment.destinationCep(), shipment.originCep())) {
                case TableSelection.NoTable n -> new NoTable();
                case TableSelection.RouteNotCovered r -> new RouteNotCovered(r.tableId(), r.tableVersion());
                case TableSelection.Found f -> toOutcome(FreightCalculator.calculate(f.tableId(), f.tableVersion(), f.table(), input));
            };
        } catch (InvalidShipmentException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    static ShipmentInput toInput(Shipment s) {
        return new ShipmentInput(s.destinationCep(), s.originCep(), s.actualWeightKg(), s.cubedWeightKg(),
                s.volumeM3(), s.cargoValue(), s.icmsRatePercent());
    }

    static Outcome toOutcome(CalculationResult result) {
        return switch (result) {
            case CalculationResult.RouteNotCovered r -> new RouteNotCovered(r.tableId(), r.tableVersion());
            case CalculationResult.Calculated c -> new Calculated(c.tableId(), c.tableVersion(), c.routeName(),
                    c.bandFromKg(), c.bandToKg(), c.actualWeightKg(), c.cubedWeightKg(), c.chargeableWeightKg(),
                    c.components().stream().map(x -> new Component(x.code(), x.amount())).toList(), c.subtotal(),
                    c.icmsAdded(), c.total());
        };
    }
}

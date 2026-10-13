package br.com.easyaudit.freight.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.freight.domain.CalculationResult.Calculated;
import br.com.easyaudit.freight.domain.CalculationResult.Component;
import br.com.easyaudit.freight.domain.CalculationResult.RouteNotCovered;
import br.com.easyaudit.freight.domain.CompiledTable.Area;
import br.com.easyaudit.freight.domain.CompiledTable.AreaType;
import br.com.easyaudit.freight.domain.CompiledTable.Band;
import br.com.easyaudit.freight.domain.CompiledTable.Route;

/**
 * Cálculo determinístico do frete esperado de um embarque em uma versão de tabela. Função pura sobre
 * {@link BigDecimal}: as divisões intermediárias usam escala 10 com {@code HALF_UP} e cada componente é arredondado a
 * 2 casas com {@code HALF_UP}; o total é a soma dos componentes arredondados. As mesmas entradas e a mesma versão de
 * tabela sempre dão o mesmo resultado.
 */
public final class FreightCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int INTERMEDIATE_SCALE = 10;

    private FreightCalculator() {
    }

    /** @throws InvalidShipmentException peso real ou valor da carga negativos, CEP inválido, alíquota fora de 0–100 */
    public static CalculationResult calculate(UUID tableId, int tableVersion, CompiledTable table, ShipmentInput in) {
        validate(in);
        Optional<Route> found = table.findRoute(in.destinationCep(), in.originCep());
        if (found.isEmpty()) {
            return new RouteNotCovered(tableId, tableVersion);
        }
        Route route = found.get();

        // peso taxado: o maior entre o real e o cubado (informado, ou volume × fator de cubagem)
        BigDecimal cubed = in.cubedWeightKg() != null ? in.cubedWeightKg()
                : in.volumeM3() != null ? in.volumeM3().multiply(table.cubingFactor()) : null;
        BigDecimal chargeable = cubed != null ? in.actualWeightKg().max(cubed) : in.actualWeightKg();

        List<Component> components = new ArrayList<>();
        Band band = bandFor(route, chargeable);
        BigDecimal freightWeight = round(freightWeight(route, band, chargeable));
        components.add(new Component("FREIGHT_WEIGHT", freightWeight));
        addIfPositive(components, "AD_VALOREM",
                percentWithMinimum(in.cargoValue(), route.adValoremPercent(), route.adValoremMinimum()));
        addIfPositive(components, "GRIS", percentWithMinimum(in.cargoValue(), route.grisPercent(), route.grisMinimum()));
        addIfPositive(components, "TOLL", route.tollPer100Kg().multiply(fractionsOf100Kg(chargeable)));
        int destination = Integer.parseInt(in.destinationCep());
        for (AreaType type : AreaType.values()) {
            // cada tipo de área vale no máximo uma vez
            table.areas().stream().filter(a -> a.type() == type && destination >= a.cepFrom() && destination <= a.cepTo())
                    .findFirst().ifPresent(area -> addIfPositive(components, type.name(), areaCharge(area, freightWeight)));
        }
        addIfPositive(components, "DISPATCH_FEE", table.dispatchFee());

        BigDecimal subtotal = sum(components);
        if (subtotal.compareTo(table.minimumFreight()) < 0) {
            BigDecimal adjustment = round(table.minimumFreight().subtract(subtotal));
            components.add(new Component("MINIMUM_ADJUSTMENT", adjustment));
            subtotal = sum(components);
        }

        boolean icmsAdded = !table.includesIcms() && in.icmsRatePercent() != null && in.icmsRatePercent().signum() > 0;
        BigDecimal total = subtotal;
        if (icmsAdded) {
            BigDecimal divisor = BigDecimal.ONE.subtract(
                    in.icmsRatePercent().divide(HUNDRED, INTERMEDIATE_SCALE, RoundingMode.HALF_UP));
            BigDecimal withIcms = round(subtotal.divide(divisor, INTERMEDIATE_SCALE, RoundingMode.HALF_UP));
            components.add(new Component("ICMS", withIcms.subtract(subtotal)));
            total = sum(components);
        }
        return new Calculated(tableId, tableVersion, route.name(), band.fromKg(), band.toKg(), in.actualWeightKg(),
                cubed, chargeable, List.copyOf(components), subtotal, icmsAdded, total);
    }

    // ---- componentes

    /** Faixa que contém o peso (maior que o início e até o fim, a primeira inclui o zero); a última se passou dela. */
    private static Band bandFor(Route route, BigDecimal weight) {
        List<Band> bands = route.bands();
        for (int i = 0; i < bands.size(); i++) {
            Band band = bands.get(i);
            boolean aboveStart = i == 0 ? weight.compareTo(band.fromKg()) >= 0 : weight.compareTo(band.fromKg()) > 0;
            if (aboveStart && weight.compareTo(band.toKg()) <= 0) {
                return band;
            }
        }
        return bands.get(bands.size() - 1);
    }

    private static BigDecimal freightWeight(Route route, Band band, BigDecimal weight) {
        Band last = route.bands().get(route.bands().size() - 1);
        if (weight.compareTo(last.toKg()) > 0) {
            return last.price().add(route.excessPerKg().multiply(weight.subtract(last.toKg())));
        }
        return band.price();
    }

    private static BigDecimal percentWithMinimum(BigDecimal base, BigDecimal percent, BigDecimal minimum) {
        BigDecimal byPercent = base.multiply(percent).divide(HUNDRED, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
        return byPercent.max(minimum);
    }

    /** Frações de 100 kg do peso taxado, arredondadas para cima. */
    private static BigDecimal fractionsOf100Kg(BigDecimal weight) {
        return weight.divide(HUNDRED, 0, RoundingMode.CEILING);
    }

    private static BigDecimal areaCharge(Area area, BigDecimal freightWeight) {
        BigDecimal byPercent = freightWeight.multiply(area.percentOverFreightWeight())
                .divide(HUNDRED, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
        return byPercent.max(area.minimum());
    }

    private static void addIfPositive(List<Component> components, String code, BigDecimal amount) {
        BigDecimal rounded = round(amount);
        if (rounded.signum() > 0) {
            components.add(new Component(code, rounded));
        }
    }

    private static BigDecimal sum(List<Component> components) {
        return components.stream().map(Component::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    // ---- validação

    /** Valida as entradas do cálculo. @throws InvalidShipmentException com todos os campos inválidos */
    public static void validate(ShipmentInput in) {
        List<InvalidShipmentException.FieldIssue> issues = new ArrayList<>();
        if (in.actualWeightKg() == null || in.actualWeightKg().signum() < 0) {
            issues.add(new InvalidShipmentException.FieldIssue("actualWeightKg",
                    "O peso real é obrigatório e não pode ser negativo."));
        }
        if (in.cargoValue() == null || in.cargoValue().signum() < 0) {
            issues.add(new InvalidShipmentException.FieldIssue("cargoValue",
                    "O valor da carga é obrigatório e não pode ser negativo."));
        }
        if (in.cubedWeightKg() != null && in.cubedWeightKg().signum() < 0) {
            issues.add(new InvalidShipmentException.FieldIssue("cubedWeightKg", "O peso cubado não pode ser negativo."));
        }
        if (in.volumeM3() != null && in.volumeM3().signum() < 0) {
            issues.add(new InvalidShipmentException.FieldIssue("volumeM3", "O volume não pode ser negativo."));
        }
        if (in.icmsRatePercent() != null
                && (in.icmsRatePercent().signum() < 0 || in.icmsRatePercent().compareTo(HUNDRED) >= 0)) {
            issues.add(new InvalidShipmentException.FieldIssue("icmsRatePercent",
                    "A alíquota de ICMS deve estar entre 0 e 100 (exclusive)."));
        }
        if (in.destinationCep() == null || !in.destinationCep().matches("[0-9]{8}")) {
            issues.add(new InvalidShipmentException.FieldIssue("destinationCep", "CEP de destino inválido (8 dígitos)."));
        }
        if (in.originCep() != null && !in.originCep().matches("[0-9]{8}")) {
            issues.add(new InvalidShipmentException.FieldIssue("originCep", "CEP de origem inválido (8 dígitos)."));
        }
        if (!issues.isEmpty()) {
            throw new InvalidShipmentException(issues);
        }
    }
}

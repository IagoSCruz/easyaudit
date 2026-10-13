package br.com.easyaudit.documents.infrastructure.xml;

import java.math.BigDecimal;
import java.util.List;

import br.com.easyaudit.contracts.api.model.CargoQuantity;
import br.com.easyaudit.contracts.api.model.DerivedMeasures;
import br.com.easyaudit.shared.ComponentNames;

/**
 * Deriva peso real, peso cubado e volume das quantidades de carga do CT-e ({@code infQ}). Unidades ({@code cUnid}):
 * 00 = m³, 01 = kg, 02 = toneladas (×1000). O tipo de medida é comparado sem maiúsculas nem acentos: contendo
 * {@code CUBAD} é peso cubado; contendo {@code BRUTO}, {@code REAL}, {@code AFERIDO} ou {@code DECLARADO} é peso
 * real. Havendo vários valores do mesmo tipo, vale o maior; sem nenhum peso real classificado, vale o primeiro
 * peso em kg que não é cubado.
 */
public final class MeasureDeriver {

    private static final List<String> REAL_MARKERS = List.of("BRUTO", "REAL", "AFERIDO", "DECLARADO");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private MeasureDeriver() {
    }

    public static DerivedMeasures derive(List<CargoQuantity> quantities) {
        BigDecimal real = null;
        BigDecimal cubed = null;
        BigDecimal volume = null;
        BigDecimal firstNonCubedWeight = null;

        for (CargoQuantity q : quantities) {
            BigDecimal value = q.getQuantity();
            if (value == null) {
                continue;
            }
            String unit = q.getUnitCode();
            String measure = ComponentNames.normalize(q.getMeasureType());
            if ("00".equals(unit)) {
                volume = max(volume, value);
                continue;
            }
            BigDecimal kg = switch (unit == null ? "" : unit) {
                case "01" -> value;
                case "02" -> value.multiply(THOUSAND);
                default -> null; // unidades, litros e MMBTU não são peso
            };
            if (kg == null) {
                continue;
            }
            if (measure.contains("CUBAD")) {
                cubed = max(cubed, kg);
            } else if (REAL_MARKERS.stream().anyMatch(measure::contains)) {
                real = max(real, kg);
            } else if (firstNonCubedWeight == null) {
                firstNonCubedWeight = kg;
            }
        }
        if (real == null) {
            real = firstNonCubedWeight;
        }
        return new DerivedMeasures().actualWeightKg(real).cubedWeightKg(cubed).volumeM3(volume);
    }

    private static BigDecimal max(BigDecimal current, BigDecimal candidate) {
        return current == null || candidate.compareTo(current) > 0 ? candidate : current;
    }
}

package br.com.easyaudit.freight.domain;

import java.math.BigDecimal;

/**
 * Entradas do cálculo de frete. {@code cubedWeightKg}, {@code volumeM3}, {@code originCep} e
 * {@code icmsRatePercent} são opcionais.
 */
public record ShipmentInput(String destinationCep, String originCep, BigDecimal actualWeightKg,
        BigDecimal cubedWeightKg, BigDecimal volumeM3, BigDecimal cargoValue, BigDecimal icmsRatePercent) {
}

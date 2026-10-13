package br.com.easyaudit.freight.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cálculo do frete esperado de um embarque: seleciona a tabela vigente na data de emissão e calcula. Vale para o
 * tenant do contexto corrente. É a fachada que a auditoria usa.
 */
public interface FreightCalculation {

    /**
     * Entradas do embarque. {@code cubedWeightKg}, {@code volumeM3}, {@code originCep} e {@code icmsRatePercent}
     * são opcionais.
     */
    record Shipment(String destinationCep, String originCep, BigDecimal actualWeightKg, BigDecimal cubedWeightKg,
            BigDecimal volumeM3, BigDecimal cargoValue, BigDecimal icmsRatePercent) {
    }

    record Component(String code, BigDecimal amount) {
    }

    /** Resultado do cálculo. */
    sealed interface Outcome permits NoTable, RouteNotCovered, Calculated {
    }

    /** Não há tabela vigente da transportadora na data de emissão. */
    record NoTable() implements Outcome {
    }

    /** Há tabela vigente, mas nenhuma rota cobre o CEP de destino. */
    record RouteNotCovered(UUID tableId, int tableVersion) implements Outcome {
    }

    /** Detalhamento do cálculo, com a tabela e a versão usadas. */
    record Calculated(UUID tableId, int tableVersion, String routeName, BigDecimal bandFromKg, BigDecimal bandToKg,
            BigDecimal actualWeightKg, BigDecimal cubedWeightKg, BigDecimal chargeableWeightKg,
            List<Component> components, BigDecimal subtotal, boolean icmsAdded, BigDecimal total) implements Outcome {

        /** Valor do componente, ou nulo se ele não entrou no cálculo. */
        public BigDecimal amountOf(String code) {
            return components.stream().filter(c -> c.code().equals(code)).map(Component::amount).findFirst()
                    .orElse(null);
        }
    }

    /**
     * @throws IllegalArgumentException entradas inválidas (peso ou valor da carga negativos, CEP inválido, alíquota
     *                                   fora de 0–100); a mensagem lista os campos
     */
    Outcome calculate(UUID carrierId, OffsetDateTime issuedAt, Shipment shipment);
}

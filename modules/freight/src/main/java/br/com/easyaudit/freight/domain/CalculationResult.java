package br.com.easyaudit.freight.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Resultado do cálculo: ou o detalhamento ({@link Calculated}) ou {@link RouteNotCovered}, sem valores. */
public sealed interface CalculationResult {

    /** Um componente do frete com o código e o valor arredondado a 2 casas. */
    record Component(String code, BigDecimal amount) {
    }

    /**
     * @param bandFromKg    início da faixa de peso aplicada
     * @param bandToKg      fim da faixa aplicada (a última, quando o peso passou dela)
     * @param cubedWeightKg peso cubado usado; nulo se não houve peso cubado nem volume
     * @param subtotal      soma dos componentes antes do ICMS (já com o ajuste ao frete mínimo)
     * @param icmsAdded     verdadeiro quando o ICMS foi somado "por dentro" ao subtotal
     */
    record Calculated(UUID tableId, int tableVersion, String routeName, BigDecimal bandFromKg, BigDecimal bandToKg,
            BigDecimal actualWeightKg, BigDecimal cubedWeightKg, BigDecimal chargeableWeightKg,
            List<Component> components, BigDecimal subtotal, boolean icmsAdded, BigDecimal total)
            implements CalculationResult {

        /** Valor do componente, se presente. */
        public BigDecimal amountOf(String code) {
            return components.stream().filter(c -> c.code().equals(code)).map(Component::amount).findFirst()
                    .orElse(null);
        }
    }

    record RouteNotCovered(UUID tableId, int tableVersion) implements CalculationResult {
    }
}

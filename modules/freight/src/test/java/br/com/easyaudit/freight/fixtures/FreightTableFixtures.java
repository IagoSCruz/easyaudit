package br.com.easyaudit.freight.fixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import br.com.easyaudit.contracts.api.model.FreightRoute;
import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.contracts.api.model.WeightBand;

/** Tabelas de frete de teste, reutilizáveis por outros módulos (test-jar). */
public final class FreightTableFixtures {

    private FreightTableFixtures() {
    }

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    public static WeightBand band(String from, String to, String price) {
        return new WeightBand().fromKg(n(from)).toKg(n(to)).price(n(price));
    }

    /**
     * A tabela de referência T da spec de cálculo: fator de cubagem 300 kg/m³; uma rota para os CEPs
     * 01000000–09999999; faixas 0–5 = 20.00, 5–10 = 28.00 e 10–30 = 45.00; excedente 1.20/kg; ad valorem 0,30 %
     * (mínimo 2.00); GRIS 0,20 % (mínimo 1.50); pedágio 3.10 a cada 100 kg; despacho 5.00; frete mínimo 40.00; valores
     * sem ICMS; vigente desde 2026-01-01.
     */
    public static FreightTableContent tableT() {
        FreightRoute route = new FreightRoute().name("SP capital").destinationCepFrom("01000000").destinationCepTo("09999999")
                .weightBands(new ArrayList<>(List.of(band("0", "5", "20.00"), band("5", "10", "28.00"), band("10", "30", "45.00"))))
                .excessPerKg(n("1.20")).adValoremPercent(n("0.30")).adValoremMinimum(n("2.00")).grisPercent(n("0.20"))
                .grisMinimum(n("1.50")).tollPer100Kg(n("3.10"));
        return new FreightTableContent().name("Tabela T").validFrom(LocalDate.of(2026, 1, 1)).includesIcms(false)
                .cubingFactor(n("300")).minimumFreight(n("40.00")).dispatchFee(n("5.00"))
                .routes(new ArrayList<>(List.of(route))).specialAreas(new ArrayList<>());
    }
}

package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import br.com.easyaudit.freight.application.TableCompiler;
import br.com.easyaudit.freight.domain.CalculationResult.Calculated;
import br.com.easyaudit.freight.domain.CompiledTable;
import br.com.easyaudit.freight.domain.FreightCalculator;
import br.com.easyaudit.freight.domain.ShipmentInput;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Propriedades do cálculo de frete (testes de propriedade com jqwik). */
class FreightCalculatorPropertyTest {

    private static final UUID TABLE = UUID.randomUUID();
    private static final CompiledTable T = TableCompiler.compile(FreightTableValidatorTest.tableT());

    @Provide
    Arbitrary<BigDecimal> weights() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("2000")).ofScale(3);
    }

    @Provide
    Arbitrary<BigDecimal> money() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("200000")).ofScale(2);
    }

    @Provide
    Arbitrary<BigDecimal> volumes() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("20")).ofScale(3).injectNull(0.3);
    }

    @Provide
    Arbitrary<BigDecimal> icmsRates() {
        return Arbitraries.bigDecimals().between(BigDecimal.ZERO, new BigDecimal("40")).ofScale(2).injectNull(0.3);
    }

    @Provide
    Arbitrary<ShipmentInput> shipments() {
        return Combinators.combine(weights(), volumes(), money(), icmsRates())
                .as((weight, volume, cargo, icms) -> new ShipmentInput("01310100", null, weight, null, volume, cargo, icms));
    }

    private static Calculated calc(ShipmentInput in) {
        return (Calculated) FreightCalculator.calculate(TABLE, 1, T, in);
    }

    @Property
    void totalIsTheSumOfTheComponents(@ForAll("shipments") ShipmentInput in) {
        Calculated r = calc(in);
        BigDecimal sum = r.components().stream().map(c -> c.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(r.total()).isEqualByComparingTo(sum);
        r.components().forEach(c -> assertThat(c.amount().scale()).isEqualTo(2));
    }

    @Property
    void chargeableWeightIsNeverBelowTheActualWeight(@ForAll("shipments") ShipmentInput in) {
        Calculated r = calc(in);
        assertThat(r.chargeableWeightKg()).isGreaterThanOrEqualTo(in.actualWeightKg());
        if (r.cubedWeightKg() != null) {
            assertThat(r.chargeableWeightKg()).isGreaterThanOrEqualTo(r.cubedWeightKg());
        }
    }

    @Property
    void freightWeightNeverDecreasesWithTheWeight(@ForAll("weights") BigDecimal a, @ForAll("weights") BigDecimal b,
            @ForAll("money") BigDecimal cargo) {
        BigDecimal low = a.min(b);
        BigDecimal high = a.max(b);
        BigDecimal atLow = calc(new ShipmentInput("01310100", null, low, null, null, cargo, null)).amountOf("FREIGHT_WEIGHT");
        BigDecimal atHigh = calc(new ShipmentInput("01310100", null, high, null, null, cargo, null)).amountOf("FREIGHT_WEIGHT");
        assertThat(atHigh).isGreaterThanOrEqualTo(atLow);
    }

    @Property
    void theSameInputsAlwaysGiveTheSameResult(@ForAll("shipments") ShipmentInput in) {
        assertThat(calc(in)).isEqualTo(calc(in));
    }

    @Property
    void theSubtotalNeverFallsBelowTheMinimumFreight(@ForAll("shipments") ShipmentInput in) {
        assertThat(calc(in).subtotal()).isGreaterThanOrEqualTo(new BigDecimal("40.00"));
    }

    @Property
    void addingIcmsNeverLowersTheTotal(@ForAll("shipments") ShipmentInput in) {
        ShipmentInput without = new ShipmentInput(in.destinationCep(), in.originCep(), in.actualWeightKg(),
                in.cubedWeightKg(), in.volumeM3(), in.cargoValue(), null);
        assertThat(calc(in).total()).isGreaterThanOrEqualTo(calc(without).total());
    }
}

package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.contracts.api.model.SpecialArea;
import br.com.easyaudit.contracts.api.model.SpecialAreaType;
import br.com.easyaudit.freight.application.TableCompiler;
import br.com.easyaudit.freight.domain.CalculationResult;
import br.com.easyaudit.freight.domain.CalculationResult.Calculated;
import br.com.easyaudit.freight.domain.CalculationResult.RouteNotCovered;
import br.com.easyaudit.freight.domain.CompiledTable;
import br.com.easyaudit.freight.domain.FreightCalculator;
import br.com.easyaudit.freight.domain.InvalidShipmentException;
import br.com.easyaudit.freight.domain.ShipmentInput;
import org.junit.jupiter.api.Test;

/** Reproduz os cenários da spec freight-calculation com a tabela de referência T. */
class FreightCalculatorTest {

    private static final UUID TABLE = UUID.randomUUID();

    private static BigDecimal n(String v) {
        return v == null ? null : new BigDecimal(v);
    }

    private static CompiledTable tableT() {
        return TableCompiler.compile(FreightTableValidatorTest.tableT());
    }

    private static Calculated calc(CompiledTable table, String dest, String actual, String cubed, String volume,
            String cargo, String icms) {
        CalculationResult result = FreightCalculator.calculate(TABLE, 1, table,
                new ShipmentInput(dest, null, n(actual), n(cubed), n(volume), n(cargo), n(icms)));
        return (Calculated) result;
    }

    private static Calculated calc(String actual, String cubed, String volume, String cargo, String icms) {
        return calc(tableT(), "01310100", actual, cubed, volume, cargo, icms);
    }

    @Test
    void invalidInputsAreRejectedNamingTheField() {
        assertThatThrownBy(() -> calc("-1", null, null, "100", null)).isInstanceOf(InvalidShipmentException.class)
                .satisfies(e -> assertThat(((InvalidShipmentException) e).issues()).extracting(i -> i.field())
                        .containsExactly("actualWeightKg"));
        assertThatThrownBy(() -> calc("1", null, null, "-5", null)).isInstanceOf(InvalidShipmentException.class)
                .satisfies(e -> assertThat(((InvalidShipmentException) e).issues()).extracting(i -> i.field())
                        .containsExactly("cargoValue"));
        assertThatThrownBy(() -> calc("1", null, null, "5", "100")).isInstanceOf(InvalidShipmentException.class);
    }

    @Test
    void cubedGreaterThanActualWins() {
        Calculated r = calc("12.5", null, "0.05", "1000", null);
        assertThat(r.cubedWeightKg()).isEqualByComparingTo("15");
        assertThat(r.chargeableWeightKg()).isEqualByComparingTo("15");
    }

    @Test
    void informedCubedWeightPrevailsOverVolume() {
        Calculated r = calc("12.5", "18", "0.05", "1000", null);
        assertThat(r.cubedWeightKg()).isEqualByComparingTo("18");
        assertThat(r.chargeableWeightKg()).isEqualByComparingTo("18");
    }

    @Test
    void withoutCubedWeightOrVolumeTheActualWeightIsUsed() {
        Calculated r = calc("12.5", null, null, "1000", null);
        assertThat(r.cubedWeightKg()).isNull();
        assertThat(r.chargeableWeightKg()).isEqualByComparingTo("12.5");
    }

    @Test
    void actualWeightWinsWhenGreaterThanCubed() {
        assertThat(calc("40", null, "0.05", "1000", null).chargeableWeightKg()).isEqualByComparingTo("40");
    }

    @Test
    void bandBoundaryBelongsToTheLowerBand() {
        assertThat(calc("5", null, null, "100", null).amountOf("FREIGHT_WEIGHT")).isEqualByComparingTo("20.00");
        Calculated r = calc("5", null, null, "100", null);
        assertThat(r.bandFromKg()).isEqualByComparingTo("0");
        assertThat(r.bandToKg()).isEqualByComparingTo("5");
    }

    @Test
    void justAboveTheBoundaryUsesTheNextBand() {
        assertThat(calc("5.001", null, null, "100", null).amountOf("FREIGHT_WEIGHT")).isEqualByComparingTo("28.00");
        assertThat(calc("10", null, null, "100", null).amountOf("FREIGHT_WEIGHT")).isEqualByComparingTo("28.00");
        assertThat(calc("30", null, null, "100", null).amountOf("FREIGHT_WEIGHT")).isEqualByComparingTo("45.00");
    }

    @Test
    void zeroWeightUsesTheFirstBand() {
        assertThat(calc("0", null, null, "100", null).amountOf("FREIGHT_WEIGHT")).isEqualByComparingTo("20.00");
    }

    @Test
    void excessWeightAboveTheLastBand() {
        assertThat(calc("42", null, null, "100", null).amountOf("FREIGHT_WEIGHT")).isEqualByComparingTo("59.40");
    }

    @Test
    void adValoremAndGrisByPercent() {
        Calculated r = calc("5", null, null, "1000.00", null);
        assertThat(r.amountOf("AD_VALOREM")).isEqualByComparingTo("3.00");
        assertThat(r.amountOf("GRIS")).isEqualByComparingTo("2.00");
    }

    @Test
    void adValoremAndGrisMinimumsApply() {
        Calculated r = calc("5", null, null, "50.00", null);
        assertThat(r.amountOf("AD_VALOREM")).isEqualByComparingTo("2.00");
        assertThat(r.amountOf("GRIS")).isEqualByComparingTo("1.50");
    }

    @Test
    void tollIsChargedPerStartedHundredKilos() {
        assertThat(calc("230", null, null, "100", null).amountOf("TOLL")).isEqualByComparingTo("9.30");
        assertThat(calc("15", null, null, "100", null).amountOf("TOLL")).isEqualByComparingTo("3.10");
        assertThat(calc("100", null, null, "100", null).amountOf("TOLL")).isEqualByComparingTo("3.10");
        assertThat(calc("100.5", null, null, "100", null).amountOf("TOLL")).isEqualByComparingTo("6.20");
    }

    @Test
    void specialAreaTdeUsesTheMinimumWhenGreaterThanThePercentage() {
        FreightTableContent content = FreightTableValidatorTest.tableT();
        content.getSpecialAreas().add(new SpecialArea().type(SpecialAreaType.TDE).cepFrom("05500000").cepTo("05599999")
                .percentOverFreightWeight(n("10")).minimum(n("15.00")));
        CompiledTable table = TableCompiler.compile(content);

        Calculated inArea = calc(table, "05501000", "20", null, null, "100", null);
        assertThat(inArea.amountOf("FREIGHT_WEIGHT")).isEqualByComparingTo("45.00");
        assertThat(inArea.amountOf("TDE")).isEqualByComparingTo("15.00");
        // fora da área não há TDE
        assertThat(calc(table, "01310100", "20", null, null, "100", null).amountOf("TDE")).isNull();
    }

    @Test
    void specialAreaPercentageWinsWhenGreater() {
        FreightTableContent content = FreightTableValidatorTest.tableT();
        content.getSpecialAreas().add(new SpecialArea().type(SpecialAreaType.TDA).cepFrom("05500000").cepTo("05599999")
                .percentOverFreightWeight(n("50")).minimum(n("1.00")));
        Calculated r = calc(TableCompiler.compile(content), "05501000", "20", null, null, "100", null);
        assertThat(r.amountOf("TDA")).isEqualByComparingTo("22.50");
    }

    @Test
    void eachAreaTypeIsAppliedAtMostOnce() {
        FreightTableContent content = FreightTableValidatorTest.tableT();
        content.getSpecialAreas().add(new SpecialArea().type(SpecialAreaType.TDE).cepFrom("05500000").cepTo("05599999")
                .percentOverFreightWeight(n("10")).minimum(n("15.00")));
        content.getSpecialAreas().add(new SpecialArea().type(SpecialAreaType.TDE).cepFrom("05501000").cepTo("05502000")
                .percentOverFreightWeight(n("10")).minimum(n("30.00")));
        Calculated r = calc(TableCompiler.compile(content), "05501500", "20", null, null, "100", null);
        assertThat(r.components().stream().filter(c -> c.code().equals("TDE"))).hasSize(1);
        assertThat(r.amountOf("TDE")).isEqualByComparingTo("15.00");
    }

    @Test
    void dispatchFeeAndMinimumAdjustment() {
        Calculated r = calc("1", null, null, "50.00", null);
        assertThat(r.amountOf("DISPATCH_FEE")).isEqualByComparingTo("5.00");
        assertThat(r.amountOf("MINIMUM_ADJUSTMENT")).isEqualByComparingTo("8.40");
        assertThat(r.subtotal()).isEqualByComparingTo("40.00");
        assertThat(r.total()).isEqualByComparingTo("40.00");
        // 20.00 + 2.00 + 1.50 + 3.10 + 5.00 = 31.60 antes do ajuste
        assertThat(r.components().stream().filter(c -> !c.code().equals("MINIMUM_ADJUSTMENT")).map(c -> c.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("31.60");
    }

    @Test
    void noAdjustmentWhenTheSubtotalReachesTheMinimum() {
        Calculated r = calc("12.5", null, "0.05", "1000", null);
        assertThat(r.amountOf("MINIMUM_ADJUSTMENT")).isNull();
        assertThat(r.subtotal()).isEqualByComparingTo("58.10");
    }

    @Test
    void icmsIsAddedInsideTheTotal() {
        Calculated r = calc("12.5", null, "0.05", "1000", "12");
        assertThat(r.subtotal()).isEqualByComparingTo("58.10");
        assertThat(r.total()).isEqualByComparingTo("66.02");
        assertThat(r.amountOf("ICMS")).isEqualByComparingTo("7.92");
        assertThat(r.icmsAdded()).isTrue();
    }

    @Test
    void icmsIsNotAddedWhenTheTableAlreadyIncludesIt() {
        FreightTableContent content = FreightTableValidatorTest.tableT();
        content.setIncludesIcms(true);
        Calculated r = calc(TableCompiler.compile(content), "01310100", "12.5", null, "0.05", "1000", "12");
        assertThat(r.total()).isEqualByComparingTo(r.subtotal());
        assertThat(r.amountOf("ICMS")).isNull();
        assertThat(r.icmsAdded()).isFalse();
    }

    @Test
    void icmsIsNotAddedWithoutARate() {
        Calculated none = calc("12.5", null, "0.05", "1000", null);
        Calculated zero = calc("12.5", null, "0.05", "1000", "0");
        assertThat(none.icmsAdded()).isFalse();
        assertThat(zero.icmsAdded()).isFalse();
        assertThat(zero.total()).isEqualByComparingTo(none.total());
    }

    @Test
    void sameInputsAlwaysGiveTheSameResult() {
        Calculated a = calc("12.5", null, "0.05", "1000", "12");
        Calculated b = calc("12.5", null, "0.05", "1000", "12");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void totalIsTheSumOfTheRoundedComponents() {
        Calculated r = calc("37.333", null, null, "777.77", "18");
        BigDecimal sum = r.components().stream().map(c -> c.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(r.total()).isEqualByComparingTo(sum);
        r.components().forEach(c -> assertThat(c.amount().scale()).isEqualTo(2));
    }

    @Test
    void uncoveredCepYieldsRouteNotCoveredWithoutComponents() {
        CalculationResult result = FreightCalculator.calculate(TABLE, 3, tableT(),
                new ShipmentInput("69900000", null, n("5"), null, null, n("100"), null));
        assertThat(result).isInstanceOf(RouteNotCovered.class);
        assertThat(((RouteNotCovered) result).tableVersion()).isEqualTo(3);
    }

    @Test
    void resultCarriesTheTableVersionRouteAndBand() {
        Calculated r = calc("7", null, null, "100", null);
        assertThat(r.tableId()).isEqualTo(TABLE);
        assertThat(r.tableVersion()).isEqualTo(1);
        assertThat(r.routeName()).isEqualTo("SP capital");
        assertThat(r.bandFromKg()).isEqualByComparingTo("5");
        assertThat(r.bandToKg()).isEqualByComparingTo("10");
    }
}

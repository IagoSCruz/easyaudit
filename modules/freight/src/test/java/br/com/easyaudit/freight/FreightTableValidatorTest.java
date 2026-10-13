package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import br.com.easyaudit.contracts.api.model.FreightRoute;
import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.contracts.api.model.SpecialArea;
import br.com.easyaudit.contracts.api.model.SpecialAreaType;
import br.com.easyaudit.contracts.api.model.WeightBand;
import br.com.easyaudit.freight.application.FreightTableValidator;
import br.com.easyaudit.freight.application.FreightTableValidator.Issue;
import org.junit.jupiter.api.Test;

class FreightTableValidatorTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    static WeightBand band(String from, String to, String price) {
        return br.com.easyaudit.freight.fixtures.FreightTableFixtures.band(from, to, price);
    }

    /** A tabela de referência T da spec de cálculo. */
    static FreightTableContent tableT() {
        return br.com.easyaudit.freight.fixtures.FreightTableFixtures.tableT();
    }

    private static FreightRoute route(String from, String to) {
        return new FreightRoute().destinationCepFrom(from).destinationCepTo(to)
                .weightBands(new ArrayList<>(List.of(band("0", "10", "10.00"))));
    }

    private static List<String> paths(FreightTableContent table) {
        return FreightTableValidator.validate(table).stream().map(Issue::path).toList();
    }

    @Test
    void referenceTableIsValid() {
        assertThat(FreightTableValidator.validate(tableT())).isEmpty();
    }

    @Test
    void completeTableWithSpecialAreaIsValid() {
        FreightTableContent table = tableT();
        table.getRoutes().add(route("10000000", "19999999").originCepFrom("01000000").originCepTo("01999999"));
        table.getSpecialAreas().add(new SpecialArea().type(SpecialAreaType.TDE).cepFrom("05500000").cepTo("05599999")
                .percentOverFreightWeight(n("10")).minimum(n("15.00")));
        assertThat(FreightTableValidator.validate(table)).isEmpty();
    }

    @Test
    void cepMustHaveEightDigits() {
        FreightTableContent table = tableT();
        table.getRoutes().get(0).setDestinationCepFrom("0100000");
        table.getRoutes().get(0).setDestinationCepTo("0999999A");
        assertThat(paths(table)).contains("routes[0].destinationCepFrom", "routes[0].destinationCepTo");
    }

    @Test
    void invertedCepRangeIsRejected() {
        FreightTableContent table = tableT();
        table.getRoutes().get(0).setDestinationCepFrom("09999999");
        table.getRoutes().get(0).setDestinationCepTo("01000000");
        assertThat(FreightTableValidator.validate(table)).singleElement().satisfies(i -> {
            assertThat(i.path()).isEqualTo("routes[0].destinationCepFrom");
            assertThat(i.message()).contains("maior que o final");
        });
    }

    @Test
    void overlappingDestinationRangesForTheSameOriginAreRejected() {
        FreightTableContent table = tableT();
        table.getRoutes().clear();
        table.getRoutes().add(route("01000000", "01999999").name("Rota 1"));
        table.getRoutes().add(route("01500000", "02999999").name("Rota 2"));
        assertThat(FreightTableValidator.validate(table)).singleElement().satisfies(i -> {
            assertThat(i.path()).isEqualTo("routes[1].destinationCepFrom");
            assertThat(i.message()).contains("routes[0] (Rota 1)").contains("routes[1] (Rota 2)");
        });
    }

    @Test
    void adjacentRangesDoNotOverlap() {
        FreightTableContent table = tableT();
        table.getRoutes().clear();
        table.getRoutes().add(route("01000000", "01999999"));
        table.getRoutes().add(route("02000000", "02999999"));
        assertThat(FreightTableValidator.validate(table)).isEmpty();
    }

    @Test
    void aRouteWithOriginMayOverlapARouteWithoutOrigin() {
        FreightTableContent table = tableT();
        table.getRoutes().clear();
        table.getRoutes().add(route("01000000", "09999999"));
        table.getRoutes().add(route("01000000", "09999999").originCepFrom("01000000").originCepTo("01999999"));
        assertThat(FreightTableValidator.validate(table)).isEmpty();
    }

    @Test
    void routesWithOverlappingOriginsAndDestinationsAreRejected() {
        FreightTableContent table = tableT();
        table.getRoutes().clear();
        table.getRoutes().add(route("01000000", "01999999").originCepFrom("01000000").originCepTo("01999999"));
        table.getRoutes().add(route("01500000", "01999999").originCepFrom("01500000").originCepTo("02999999"));
        assertThat(paths(table)).containsExactly("routes[1].destinationCepFrom");
    }

    @Test
    void routesWithDisjointOriginsMayShareDestinations() {
        FreightTableContent table = tableT();
        table.getRoutes().clear();
        table.getRoutes().add(route("01000000", "01999999").originCepFrom("01000000").originCepTo("01999999"));
        table.getRoutes().add(route("01000000", "01999999").originCepFrom("20000000").originCepTo("20999999"));
        assertThat(FreightTableValidator.validate(table)).isEmpty();
    }

    @Test
    void weightBandsMustStartAtZero() {
        FreightTableContent table = tableT();
        table.getRoutes().get(0).setWeightBands(new ArrayList<>(List.of(band("1", "5", "20.00"))));
        assertThat(FreightTableValidator.validate(table)).singleElement().satisfies(i -> {
            assertThat(i.path()).isEqualTo("routes[0].weightBands[0].fromKg");
            assertThat(i.message()).contains("começar em 0 kg");
        });
    }

    @Test
    void weightBandsWithAGapAreRejectedIndicatingRouteAndBands() {
        FreightTableContent table = tableT();
        table.getRoutes().get(0).setWeightBands(new ArrayList<>(List.of(band("0", "5", "20.00"), band("6", "10", "28.00"))));
        assertThat(FreightTableValidator.validate(table)).singleElement().satisfies(i -> {
            assertThat(i.path()).isEqualTo("routes[0].weightBands[1].fromKg");
            assertThat(i.message()).contains("sem continuidade").contains("termina em 5 kg").contains("começa em 6 kg")
                    .contains("buraco");
        });
    }

    @Test
    void overlappingWeightBandsAreRejected() {
        FreightTableContent table = tableT();
        table.getRoutes().get(0).setWeightBands(new ArrayList<>(List.of(band("0", "5", "20.00"), band("4", "10", "28.00"))));
        assertThat(FreightTableValidator.validate(table)).singleElement()
                .satisfies(i -> assertThat(i.message()).contains("sobreposição"));
    }

    @Test
    void invertedWeightBandIsRejected() {
        FreightTableContent table = tableT();
        table.getRoutes().get(0).setWeightBands(new ArrayList<>(List.of(band("0", "5", "20.00"), band("10", "5", "28.00"))));
        assertThat(paths(table)).containsExactly("routes[0].weightBands[1]");
    }

    @Test
    void negativeValuesAreRejected() {
        FreightTableContent table = tableT();
        table.setMinimumFreight(n("-1"));
        table.setDispatchFee(n("-2"));
        table.getRoutes().get(0).setExcessPerKg(n("-0.1"));
        table.getRoutes().get(0).getWeightBands().get(0).setPrice(n("-20"));
        table.getRoutes().get(0).setTollPer100Kg(n("-3"));
        assertThat(paths(table)).containsExactlyInAnyOrder("minimumFreight", "dispatchFee", "routes[0].excessPerKg",
                "routes[0].weightBands[0].price", "routes[0].tollPer100Kg");
    }

    @Test
    void percentagesMustBeBetweenZeroAndOneHundred() {
        FreightTableContent table = tableT();
        table.getRoutes().get(0).setAdValoremPercent(n("150"));
        table.getRoutes().get(0).setGrisPercent(n("-1"));
        table.getSpecialAreas().add(new SpecialArea().type(SpecialAreaType.TDA).cepFrom("05500000").cepTo("05599999")
                .percentOverFreightWeight(n("100.01")));
        assertThat(paths(table)).containsExactlyInAnyOrder("routes[0].adValoremPercent", "routes[0].grisPercent",
                "specialAreas[0].percentOverFreightWeight");
        assertThat(n("100")).isNotNull();
    }

    @Test
    void cubingFactorMustBePositive() {
        FreightTableContent zero = tableT();
        zero.setCubingFactor(BigDecimal.ZERO);
        assertThat(paths(zero)).containsExactly("cubingFactor");
        FreightTableContent missing = tableT();
        missing.setCubingFactor(null);
        assertThat(paths(missing)).containsExactly("cubingFactor");
    }

    @Test
    void validityPeriodMustBeConsistent() {
        FreightTableContent table = tableT();
        table.setValidTo(LocalDate.of(2025, 12, 31));
        assertThat(paths(table)).containsExactly("validTo");
        table.setValidFrom(null);
        assertThat(paths(table)).containsExactly("validFrom");
    }

    @Test
    void structuralGapsAreReported() {
        FreightTableContent empty = new FreightTableContent();
        assertThat(paths(empty)).contains("name", "validFrom", "includesIcms", "cubingFactor", "routes");
        FreightTableContent noBands = tableT();
        noBands.getRoutes().get(0).setWeightBands(new ArrayList<>());
        assertThat(paths(noBands)).containsExactly("routes[0].weightBands");
        assertThat(FreightTableValidator.validate(null)).hasSize(1);
    }

    @Test
    void errorsAreAccumulatedNotJustTheFirst() {
        FreightTableContent table = tableT();
        table.getRoutes().get(0).setDestinationCepFrom("123");
        table.getRoutes().get(0).setAdValoremPercent(n("150"));
        assertThat(paths(table)).containsExactlyInAnyOrder("routes[0].destinationCepFrom", "routes[0].adValoremPercent");
    }

    @Test
    void specialAreaNeedsTypeAndValidCeps() {
        FreightTableContent table = tableT();
        table.getSpecialAreas().add(new SpecialArea().cepFrom("1").cepTo("05599999"));
        assertThat(paths(table)).containsExactlyInAnyOrder("specialAreas[0].type", "specialAreas[0].cepFrom");
    }
}

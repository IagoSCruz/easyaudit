package br.com.easyaudit.freight;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.freight.application.TableCompiler;
import br.com.easyaudit.freight.domain.CompiledTable;
import org.junit.jupiter.api.Test;

class CompiledTableTest {

    private static CompiledTable compile(FreightTableContent content) {
        return TableCompiler.compile(content);
    }

    @Test
    void findsTheRouteByDestinationCepWithBinarySearchOverManyRoutes() {
        FreightTableContent content = FreightTableValidatorTest.tableT();
        content.getRoutes().clear();
        // 1.000 rotas disjuntas, fora de ordem
        for (int i = 999; i >= 0; i--) {
            int from = 10_000_000 + i * 1000;
            content.getRoutes().add(new br.com.easyaudit.contracts.api.model.FreightRoute().name("R" + i)
                    .destinationCepFrom(String.format("%08d", from)).destinationCepTo(String.format("%08d", from + 999))
                    .weightBands(new java.util.ArrayList<>(List.of(FreightTableValidatorTest.band("0", "10", "10")))));
        }
        CompiledTable table = compile(content);

        assertThat(table.findRoute("10000000", null)).get().extracting(CompiledTable.Route::name).isEqualTo("R0");
        assertThat(table.findRoute("10000999", null)).get().extracting(CompiledTable.Route::name).isEqualTo("R0");
        assertThat(table.findRoute("10001000", null)).get().extracting(CompiledTable.Route::name).isEqualTo("R1");
        assertThat(table.findRoute("10999999", null)).get().extracting(CompiledTable.Route::name).isEqualTo("R999");
        assertThat(table.findRoute("09999999", null)).isEmpty();
        assertThat(table.findRoute("11000000", null)).isEmpty();
    }

    @Test
    void aRouteThatAlsoMatchesTheOriginHasPriority() {
        FreightTableContent content = FreightTableValidatorTest.tableT();
        content.getRoutes().add(new br.com.easyaudit.contracts.api.model.FreightRoute().name("SP origem 01")
                .destinationCepFrom("01000000").destinationCepTo("09999999")
                .originCepFrom("01000000").originCepTo("01999999")
                .weightBands(new java.util.ArrayList<>(List.of(FreightTableValidatorTest.band("0", "10", "10")))));
        CompiledTable table = compile(content);

        assertThat(table.findRoute("01310100", "01001000")).get().extracting(CompiledTable.Route::name).isEqualTo("SP origem 01");
        // origem fora da faixa, ou sem origem informada: cai na rota sem origem
        assertThat(table.findRoute("01310100", "20000000")).get().extracting(CompiledTable.Route::name).isEqualTo("SP capital");
        assertThat(table.findRoute("01310100", null)).get().extracting(CompiledTable.Route::name).isEqualTo("SP capital");
    }

    @Test
    void carriesTheTableLevelParameters() {
        CompiledTable table = compile(FreightTableValidatorTest.tableT());
        assertThat(table.cubingFactor()).isEqualByComparingTo("300");
        assertThat(table.minimumFreight()).isEqualByComparingTo("40.00");
        assertThat(table.dispatchFee()).isEqualByComparingTo("5.00");
        assertThat(table.includesIcms()).isFalse();
    }
}

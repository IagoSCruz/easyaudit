package br.com.easyaudit.freight.application;

import java.math.BigDecimal;
import java.util.List;

import br.com.easyaudit.contracts.api.model.FreightRoute;
import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.contracts.api.model.SpecialArea;
import br.com.easyaudit.freight.domain.CompiledTable;
import br.com.easyaudit.freight.domain.CompiledTable.Area;
import br.com.easyaudit.freight.domain.CompiledTable.AreaType;
import br.com.easyaudit.freight.domain.CompiledTable.Band;
import br.com.easyaudit.freight.domain.CompiledTable.Route;

/** Converte o conteúdo canônico, já validado, na estrutura otimizada usada pela seleção e pelo cálculo. */
public final class TableCompiler {

    private TableCompiler() {
    }

    public static CompiledTable compile(FreightTableContent content) {
        List<Route> routes = content.getRoutes().stream().map(TableCompiler::route).toList();
        List<Area> areas = (content.getSpecialAreas() == null ? List.<SpecialArea>of() : content.getSpecialAreas())
                .stream().map(a -> new Area(AreaType.valueOf(a.getType().name()), Integer.parseInt(a.getCepFrom()),
                        Integer.parseInt(a.getCepTo()), orZero(a.getPercentOverFreightWeight()), orZero(a.getMinimum())))
                .toList();
        return new CompiledTable(Boolean.TRUE.equals(content.getIncludesIcms()), content.getCubingFactor(),
                orZero(content.getMinimumFreight()), orZero(content.getDispatchFee()), routes, areas);
    }

    private static Route route(FreightRoute r) {
        boolean origin = r.getOriginCepFrom() != null;
        return new Route(r.getName(), Integer.parseInt(r.getDestinationCepFrom()),
                Integer.parseInt(r.getDestinationCepTo()), origin ? Integer.parseInt(r.getOriginCepFrom()) : null,
                origin ? Integer.parseInt(r.getOriginCepTo()) : null,
                r.getWeightBands().stream().map(b -> new Band(b.getFromKg(), b.getToKg(), b.getPrice())).toList(),
                orZero(r.getExcessPerKg()), orZero(r.getAdValoremPercent()), orZero(r.getAdValoremMinimum()),
                orZero(r.getGrisPercent()), orZero(r.getGrisMinimum()), orZero(r.getTollPer100Kg()));
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}

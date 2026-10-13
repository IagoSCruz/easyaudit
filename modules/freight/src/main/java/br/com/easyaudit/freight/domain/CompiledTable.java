package br.com.easyaudit.freight.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Conteúdo de uma versão de tabela de frete em forma otimizada para consulta: imutável, com as rotas sem origem
 * ordenadas pelo início do CEP de destino (busca binária) e as rotas com origem em lista própria. Os CEPs viram
 * inteiros. Não carrega o período de vigência, que é alterado na ativação de versões novas: o período vem do banco.
 */
public final class CompiledTable {

    /** Faixa de peso: pesos maiores que {@code fromKg} e até {@code toKg}; a primeira inclui o zero. */
    public record Band(BigDecimal fromKg, BigDecimal toKg, BigDecimal price) {
    }

    public record Route(String name, int destinationFrom, int destinationTo, Integer originFrom, Integer originTo,
            List<Band> bands, BigDecimal excessPerKg, BigDecimal adValoremPercent, BigDecimal adValoremMinimum,
            BigDecimal grisPercent, BigDecimal grisMinimum, BigDecimal tollPer100Kg) {

        boolean hasOrigin() {
            return originFrom != null;
        }

        boolean coversDestination(int cep) {
            return cep >= destinationFrom && cep <= destinationTo;
        }

        boolean coversOrigin(int cep) {
            return originFrom != null && cep >= originFrom && cep <= originTo;
        }
    }

    public enum AreaType { TDA, TDE }

    public record Area(AreaType type, int cepFrom, int cepTo, BigDecimal percentOverFreightWeight, BigDecimal minimum) {
    }

    private final boolean includesIcms;
    private final BigDecimal cubingFactor;
    private final BigDecimal minimumFreight;
    private final BigDecimal dispatchFee;
    private final List<Route> routesWithoutOrigin;
    private final List<Route> routesWithOrigin;
    private final List<Area> areas;

    public CompiledTable(boolean includesIcms, BigDecimal cubingFactor, BigDecimal minimumFreight,
            BigDecimal dispatchFee, List<Route> routes, List<Area> areas) {
        this.includesIcms = includesIcms;
        this.cubingFactor = cubingFactor;
        this.minimumFreight = minimumFreight;
        this.dispatchFee = dispatchFee;
        List<Route> without = new ArrayList<>(routes.stream().filter(r -> !r.hasOrigin()).toList());
        without.sort(Comparator.comparingInt(Route::destinationFrom));
        this.routesWithoutOrigin = List.copyOf(without);
        this.routesWithOrigin = List.copyOf(routes.stream().filter(Route::hasOrigin).toList());
        this.areas = List.copyOf(areas);
    }

    public boolean includesIcms() {
        return includesIcms;
    }

    public BigDecimal cubingFactor() {
        return cubingFactor;
    }

    public BigDecimal minimumFreight() {
        return minimumFreight;
    }

    public BigDecimal dispatchFee() {
        return dispatchFee;
    }

    public List<Area> areas() {
        return areas;
    }

    /**
     * Rota que cobre o CEP de destino. Uma rota com origem que casa com a origem informada tem prioridade sobre as
     * rotas sem origem.
     */
    public Optional<Route> findRoute(String destinationCep, String originCep) {
        int destination = Integer.parseInt(destinationCep);
        if (originCep != null) {
            int origin = Integer.parseInt(originCep);
            for (Route route : routesWithOrigin) {
                if (route.coversDestination(destination) && route.coversOrigin(origin)) {
                    return Optional.of(route);
                }
            }
        }
        // busca binária: as rotas sem origem não se sobrepõem (garantido pelo validador)
        int low = 0;
        int high = routesWithoutOrigin.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            Route route = routesWithoutOrigin.get(mid);
            if (destination < route.destinationFrom()) {
                high = mid - 1;
            } else if (destination > route.destinationTo()) {
                low = mid + 1;
            } else {
                return Optional.of(route);
            }
        }
        return Optional.empty();
    }
}

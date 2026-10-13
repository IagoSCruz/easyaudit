package br.com.easyaudit.freight.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import br.com.easyaudit.contracts.api.model.FreightRoute;
import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.contracts.api.model.SpecialArea;
import br.com.easyaudit.contracts.api.model.WeightBand;

/**
 * Valida o conteúdo de uma tabela de frete acumulando <b>todos</b> os erros, cada um com o caminho do campo
 * (por exemplo {@code routes[1].weightBands[0].fromKg}). A importação por planilha traduz o caminho para
 * aba/linha/coluna.
 */
public final class FreightTableValidator {

    /** Um erro de validação: caminho do campo e o motivo. */
    public record Issue(String path, String message) {
    }

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String CEP = "[0-9]{8}";

    private FreightTableValidator() {
    }

    public static List<Issue> validate(FreightTableContent table) {
        List<Issue> issues = new ArrayList<>();
        if (table == null) {
            issues.add(new Issue("", "O conteúdo da tabela é obrigatório."));
            return issues;
        }
        if (table.getName() == null || table.getName().isBlank()) {
            issues.add(new Issue("name", "O nome é obrigatório."));
        }
        if (table.getValidFrom() == null) {
            issues.add(new Issue("validFrom", "O início da vigência é obrigatório."));
        } else if (table.getValidTo() != null && table.getValidTo().isBefore(table.getValidFrom())) {
            issues.add(new Issue("validTo", "O fim da vigência não pode ser anterior ao início."));
        }
        if (table.getIncludesIcms() == null) {
            issues.add(new Issue("includesIcms", "Informe se os valores já incluem ICMS."));
        }
        if (table.getCubingFactor() == null || table.getCubingFactor().signum() <= 0) {
            issues.add(new Issue("cubingFactor", "O fator de cubagem deve ser maior que zero."));
        }
        nonNegative(issues, "minimumFreight", table.getMinimumFreight());
        nonNegative(issues, "dispatchFee", table.getDispatchFee());

        List<FreightRoute> routes = table.getRoutes();
        if (routes == null || routes.isEmpty()) {
            issues.add(new Issue("routes", "A tabela precisa de ao menos uma rota."));
        } else {
            for (int i = 0; i < routes.size(); i++) {
                validateRoute(issues, "routes[" + i + "]", routes.get(i));
            }
            validateOverlaps(issues, routes);
        }
        List<SpecialArea> areas = table.getSpecialAreas();
        if (areas != null) {
            for (int i = 0; i < areas.size(); i++) {
                validateArea(issues, "specialAreas[" + i + "]", areas.get(i));
            }
        }
        return issues;
    }

    // --- rotas ---

    private static void validateRoute(List<Issue> issues, String p, FreightRoute route) {
        if (route == null) {
            issues.add(new Issue(p, "Rota vazia."));
            return;
        }
        boolean destinationOk = cepRange(issues, p + ".destinationCepFrom", route.getDestinationCepFrom(),
                p + ".destinationCepTo", route.getDestinationCepTo(), true);
        boolean hasOrigin = route.getOriginCepFrom() != null || route.getOriginCepTo() != null;
        if (hasOrigin) {
            cepRange(issues, p + ".originCepFrom", route.getOriginCepFrom(), p + ".originCepTo", route.getOriginCepTo(),
                    true);
        }
        nonNegative(issues, p + ".excessPerKg", route.getExcessPerKg());
        percent(issues, p + ".adValoremPercent", route.getAdValoremPercent());
        nonNegative(issues, p + ".adValoremMinimum", route.getAdValoremMinimum());
        percent(issues, p + ".grisPercent", route.getGrisPercent());
        nonNegative(issues, p + ".grisMinimum", route.getGrisMinimum());
        nonNegative(issues, p + ".tollPer100Kg", route.getTollPer100Kg());
        validateBands(issues, p, route.getWeightBands());
        if (!destinationOk) {
            return;
        }
    }

    private static void validateBands(List<Issue> issues, String p, List<WeightBand> bands) {
        if (bands == null || bands.isEmpty()) {
            issues.add(new Issue(p + ".weightBands", "A rota precisa de ao menos uma faixa de peso."));
            return;
        }
        BigDecimal expectedStart = BigDecimal.ZERO;
        boolean chainBroken = false;
        for (int i = 0; i < bands.size(); i++) {
            String b = p + ".weightBands[" + i + "]";
            WeightBand band = bands.get(i);
            if (band == null || band.getFromKg() == null || band.getToKg() == null) {
                issues.add(new Issue(b, "A faixa precisa de início, fim e valor."));
                chainBroken = true;
                continue;
            }
            if (band.getPrice() == null) {
                issues.add(new Issue(b + ".price", "O valor da faixa é obrigatório."));
            } else if (band.getPrice().signum() < 0) {
                issues.add(new Issue(b + ".price", "O valor da faixa não pode ser negativo."));
            }
            if (band.getFromKg().signum() < 0) {
                issues.add(new Issue(b + ".fromKg", "O início da faixa não pode ser negativo."));
            }
            if (band.getFromKg().compareTo(band.getToKg()) >= 0) {
                issues.add(new Issue(b, "O início da faixa (" + plain(band.getFromKg()) + " kg) deve ser menor que o fim ("
                        + plain(band.getToKg()) + " kg)."));
                chainBroken = true;
                continue;
            }
            if (!chainBroken && band.getFromKg().compareTo(expectedStart) != 0) {
                String reason = i == 0 ? "A primeira faixa deve começar em 0 kg, mas começa em "
                        + plain(band.getFromKg()) + " kg."
                        : "Faixas de peso sem continuidade: a faixa anterior termina em " + plain(expectedStart)
                                + " kg e esta começa em " + plain(band.getFromKg()) + " kg ("
                                + (band.getFromKg().compareTo(expectedStart) > 0 ? "buraco" : "sobreposição") + ").";
                issues.add(new Issue(b + ".fromKg", reason));
                chainBroken = true;
            }
            expectedStart = band.getToKg();
        }
    }

    /** Faixas de CEP de destino sobrepostas na mesma origem (sem origem, ou origens que se interceptam). */
    private static void validateOverlaps(List<Issue> issues, List<FreightRoute> routes) {
        for (int i = 0; i < routes.size(); i++) {
            for (int j = i + 1; j < routes.size(); j++) {
                FreightRoute a = routes.get(i);
                FreightRoute b = routes.get(j);
                if (a == null || b == null || !validRange(a.getDestinationCepFrom(), a.getDestinationCepTo())
                        || !validRange(b.getDestinationCepFrom(), b.getDestinationCepTo())) {
                    continue;
                }
                if (!sameOriginScope(a, b)) {
                    continue;
                }
                if (intersects(a.getDestinationCepFrom(), a.getDestinationCepTo(), b.getDestinationCepFrom(),
                        b.getDestinationCepTo())) {
                    issues.add(new Issue("routes[" + j + "].destinationCepFrom",
                            "Faixa de CEP de destino sobreposta com a das rotas " + label(i, a) + " e " + label(j, b)
                                    + " (" + a.getDestinationCepFrom() + "–" + a.getDestinationCepTo() + " e "
                                    + b.getDestinationCepFrom() + "–" + b.getDestinationCepTo() + ")."));
                }
            }
        }
    }

    private static String label(int index, FreightRoute route) {
        return "routes[" + index + "]" + (route.getName() == null ? "" : " (" + route.getName() + ")");
    }

    private static boolean sameOriginScope(FreightRoute a, FreightRoute b) {
        boolean aOrigin = a.getOriginCepFrom() != null || a.getOriginCepTo() != null;
        boolean bOrigin = b.getOriginCepFrom() != null || b.getOriginCepTo() != null;
        if (!aOrigin && !bOrigin) {
            return true;
        }
        if (aOrigin && bOrigin) {
            return validRange(a.getOriginCepFrom(), a.getOriginCepTo()) && validRange(b.getOriginCepFrom(), b.getOriginCepTo())
                    && intersects(a.getOriginCepFrom(), a.getOriginCepTo(), b.getOriginCepFrom(), b.getOriginCepTo());
        }
        return false; // rota sem origem e rota com origem podem sobrepor: a com origem tem prioridade
    }

    // --- áreas especiais ---

    private static void validateArea(List<Issue> issues, String p, SpecialArea area) {
        if (area == null) {
            issues.add(new Issue(p, "Área vazia."));
            return;
        }
        if (area.getType() == null) {
            issues.add(new Issue(p + ".type", "O tipo da área (TDA ou TDE) é obrigatório."));
        }
        cepRange(issues, p + ".cepFrom", area.getCepFrom(), p + ".cepTo", area.getCepTo(), true);
        percent(issues, p + ".percentOverFreightWeight", area.getPercentOverFreightWeight());
        nonNegative(issues, p + ".minimum", area.getMinimum());
    }

    // --- utilitários ---

    /** Valida um par de CEPs (8 dígitos, início ≤ fim). @return {@code true} se o par é válido */
    private static boolean cepRange(List<Issue> issues, String fromPath, String from, String toPath, String to,
            boolean required) {
        boolean ok = true;
        if (from == null || !from.matches(CEP)) {
            issues.add(new Issue(fromPath, from == null ? "O CEP inicial é obrigatório."
                    : "CEP inválido: '" + from + "' (use 8 dígitos)."));
            ok = false;
        }
        if (to == null || !to.matches(CEP)) {
            issues.add(new Issue(toPath, to == null ? "O CEP final é obrigatório."
                    : "CEP inválido: '" + to + "' (use 8 dígitos)."));
            ok = false;
        }
        if (ok && from.compareTo(to) > 0) {
            issues.add(new Issue(fromPath, "O CEP inicial (" + from + ") é maior que o final (" + to + ")."));
            ok = false;
        }
        return ok;
    }

    private static boolean validRange(String from, String to) {
        return from != null && to != null && from.matches(CEP) && to.matches(CEP) && from.compareTo(to) <= 0;
    }

    private static boolean intersects(String fromA, String toA, String fromB, String toB) {
        return fromA.compareTo(toB) <= 0 && fromB.compareTo(toA) <= 0;
    }

    private static void nonNegative(List<Issue> issues, String path, BigDecimal value) {
        if (value != null && value.signum() < 0) {
            issues.add(new Issue(path, "O valor não pode ser negativo (" + plain(value) + ")."));
        }
    }

    private static void percent(List<Issue> issues, String path, BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(HUNDRED) > 0)) {
            issues.add(new Issue(path, "O percentual deve estar entre 0 e 100 (informado: " + plain(value) + ")."));
        }
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}

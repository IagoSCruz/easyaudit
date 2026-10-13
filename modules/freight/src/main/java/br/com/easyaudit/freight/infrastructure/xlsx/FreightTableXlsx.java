package br.com.easyaudit.freight.infrastructure.xlsx;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

import br.com.easyaudit.contracts.api.model.FreightRoute;
import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.contracts.api.model.SpecialArea;
import br.com.easyaudit.contracts.api.model.SpecialAreaType;
import br.com.easyaudit.contracts.api.model.WeightBand;
import br.com.easyaudit.freight.application.TableValidationException.Located;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

/**
 * Planilha modelo e importação XLSX de tabelas de frete. Abas: {@code Geral}, {@code Rotas}, {@code Faixas} e
 * {@code AreasEspeciais}, cada uma com cabeçalho na linha 1. As colunas estão documentadas em
 * {@code docs/freight-table-template.md}.
 */
public final class FreightTableXlsx {

    public static final String GENERAL = "Geral";
    public static final String ROUTES = "Rotas";
    public static final String BANDS = "Faixas";
    public static final String AREAS = "AreasEspeciais";

    private static final String[] GENERAL_HEADERS = {"Nome", "InicioVigencia", "FimVigencia", "IncluiICMS",
            "FatorCubagem", "FreteMinimo", "TaxaDespacho"};
    private static final String[] ROUTE_HEADERS = {"Rota", "CepDestinoInicio", "CepDestinoFim", "CepOrigemInicio",
            "CepOrigemFim", "ExcedentePorKg", "AdValoremPct", "AdValoremMinimo", "GrisPct", "GrisMinimo",
            "PedagioPor100Kg"};
    private static final String[] BAND_HEADERS = {"Rota", "DeKg", "AteKg", "Valor"};
    private static final String[] AREA_HEADERS = {"Tipo", "CepInicio", "CepFim", "PercentualSobreFretePeso", "Minimo"};

    private FreightTableXlsx() {
    }

    // ------------------------------------------------------------------ modelo

    /** A planilha modelo: cabeçalhos e uma linha de exemplo (válida) em cada aba. */
    public static byte[] template() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Workbook wb = new Workbook(out, "EasyAudit", "1.0");
            Worksheet general = wb.newWorksheet(GENERAL);
            header(general, GENERAL_HEADERS);
            general.value(1, 0, "Tabela 2026");
            general.value(1, 1, "2026-01-01");
            general.value(1, 3, "NAO");
            general.value(1, 4, 300);
            general.value(1, 5, 40);
            general.value(1, 6, 5);
            Worksheet routes = wb.newWorksheet(ROUTES);
            header(routes, ROUTE_HEADERS);
            routes.value(1, 0, "SP Capital");
            routes.value(1, 1, "01000000");
            routes.value(1, 2, "09999999");
            routes.value(1, 5, 1.2);
            routes.value(1, 6, 0.3);
            routes.value(1, 7, 2);
            routes.value(1, 8, 0.2);
            routes.value(1, 9, 1.5);
            routes.value(1, 10, 3.1);
            textColumns(routes, 1, 2, 3, 4);
            Worksheet bands = wb.newWorksheet(BANDS);
            header(bands, BAND_HEADERS);
            double[][] sample = {{0, 5, 20}, {5, 10, 28}, {10, 30, 45}};
            for (int i = 0; i < sample.length; i++) {
                bands.value(i + 1, 0, "SP Capital");
                bands.value(i + 1, 1, sample[i][0]);
                bands.value(i + 1, 2, sample[i][1]);
                bands.value(i + 1, 3, sample[i][2]);
            }
            Worksheet areas = wb.newWorksheet(AREAS);
            header(areas, AREA_HEADERS);
            areas.value(1, 0, "TDE");
            areas.value(1, 1, "05500000");
            areas.value(1, 2, "05599999");
            areas.value(1, 3, 10);
            areas.value(1, 4, 15);
            textColumns(areas, 1, 2);
            wb.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao gerar a planilha modelo", e);
        }
    }

    private static void header(Worksheet ws, String[] headers) {
        for (int c = 0; c < headers.length; c++) {
            ws.value(0, c, headers[c]);
            ws.style(0, c).bold().set();
            ws.width(c, Math.max(14, headers[c].length() + 4));
        }
    }

    private static void textColumns(Worksheet ws, int... columns) {
        for (int c : columns) {
            ws.style(c).format("@").set();
        }
    }

    // --------------------------------------------------------------- importação

    /** Conteúdo lido, a tradução de cada caminho de campo para a célula e os erros de leitura. */
    public record Parsed(FreightTableContent content, BiFunction<String, String, Located> locate,
            List<Located> readErrors) {
    }

    /** Planilha ilegível (não é XLSX ou está corrompida). */
    public static class UnreadableSpreadsheetException extends RuntimeException {
        public UnreadableSpreadsheetException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Parsed parse(byte[] xlsx) {
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            return new Reader(wb).read();
        } catch (IOException | RuntimeException e) {
            if (e instanceof UnreadableSpreadsheetException u) {
                throw u;
            }
            throw new UnreadableSpreadsheetException("Não foi possível ler a planilha: " + e.getMessage(), e);
        }
    }

    private static final class Reader {
        private final ReadableWorkbook wb;
        private final List<Located> errors = new ArrayList<>();
        /** caminho do campo → [aba, linha, coluna (letra), cabeçalho] */
        private final Map<String, String[]> cells = new HashMap<>();

        Reader(ReadableWorkbook wb) {
            this.wb = wb;
        }

        Parsed read() {
            FreightTableContent content = new FreightTableContent();
            readGeneral(content);
            Map<String, FreightRoute> routesByName = readRoutes(content);
            readBands(content, routesByName);
            readAreas(content);
            return new Parsed(content, this::locate, errors);
        }

        // ---- abas

        private void readGeneral(FreightTableContent content) {
            List<Row> rows = dataRows(GENERAL);
            if (rows.isEmpty()) {
                remember("name", GENERAL, 2, 0, GENERAL_HEADERS);
                return;
            }
            Row row = rows.get(0);
            content.setName(text(row, 0));
            remember("name", GENERAL, row, 0, GENERAL_HEADERS);
            content.setValidFrom(date(row, 1, "validFrom", GENERAL, GENERAL_HEADERS));
            content.setValidTo(date(row, 2, "validTo", GENERAL, GENERAL_HEADERS));
            content.setIncludesIcms(bool(row, 3, "includesIcms", GENERAL, GENERAL_HEADERS));
            content.setCubingFactor(number(row, 4, "cubingFactor", GENERAL, GENERAL_HEADERS));
            content.setMinimumFreight(number(row, 5, "minimumFreight", GENERAL, GENERAL_HEADERS));
            content.setDispatchFee(number(row, 6, "dispatchFee", GENERAL, GENERAL_HEADERS));
        }

        private Map<String, FreightRoute> readRoutes(FreightTableContent content) {
            Map<String, FreightRoute> byName = new LinkedHashMap<>();
            List<FreightRoute> routes = new ArrayList<>();
            for (Row row : dataRows(ROUTES)) {
                int i = routes.size();
                String p = "routes[" + i + "]";
                FreightRoute route = new FreightRoute();
                String name = text(row, 0);
                route.setName(name);
                remember(p, ROUTES, row, 0, ROUTE_HEADERS);
                if (name == null) {
                    error(ROUTES, row, 0, ROUTE_HEADERS, "O nome da rota é obrigatório (liga a rota às faixas de peso).");
                } else if (byName.containsKey(name)) {
                    error(ROUTES, row, 0, ROUTE_HEADERS, "Nome de rota repetido: '" + name + "'.");
                } else {
                    byName.put(name, route);
                }
                route.setDestinationCepFrom(cep(row, 1, p + ".destinationCepFrom", ROUTES, ROUTE_HEADERS));
                route.setDestinationCepTo(cep(row, 2, p + ".destinationCepTo", ROUTES, ROUTE_HEADERS));
                route.setOriginCepFrom(cep(row, 3, p + ".originCepFrom", ROUTES, ROUTE_HEADERS));
                route.setOriginCepTo(cep(row, 4, p + ".originCepTo", ROUTES, ROUTE_HEADERS));
                route.setExcessPerKg(number(row, 5, p + ".excessPerKg", ROUTES, ROUTE_HEADERS));
                route.setAdValoremPercent(number(row, 6, p + ".adValoremPercent", ROUTES, ROUTE_HEADERS));
                route.setAdValoremMinimum(number(row, 7, p + ".adValoremMinimum", ROUTES, ROUTE_HEADERS));
                route.setGrisPercent(number(row, 8, p + ".grisPercent", ROUTES, ROUTE_HEADERS));
                route.setGrisMinimum(number(row, 9, p + ".grisMinimum", ROUTES, ROUTE_HEADERS));
                route.setTollPer100Kg(number(row, 10, p + ".tollPer100Kg", ROUTES, ROUTE_HEADERS));
                route.setWeightBands(new ArrayList<>());
                // a coleção de faixas aponta para a linha da rota
                remember(p + ".weightBands", ROUTES, row, 0, ROUTE_HEADERS);
                routes.add(route);
            }
            if (routes.isEmpty()) {
                remember("routes", ROUTES, 2, 0, ROUTE_HEADERS);
            }
            content.setRoutes(routes);
            return byName;
        }

        private void readBands(FreightTableContent content, Map<String, FreightRoute> byName) {
            List<FreightRoute> routes = content.getRoutes();
            for (Row row : dataRows(BANDS)) {
                String routeName = text(row, 0);
                FreightRoute route = routeName == null ? null : byName.get(routeName);
                if (route == null) {
                    error(BANDS, row, 0, BAND_HEADERS, routeName == null ? "Informe a rota da faixa."
                            : "A rota '" + routeName + "' não existe na aba " + ROUTES + ".");
                    continue;
                }
                int routeIndex = routes.indexOf(route);
                int bandIndex = route.getWeightBands().size();
                String p = "routes[" + routeIndex + "].weightBands[" + bandIndex + "]";
                remember(p, BANDS, row, 0, BAND_HEADERS);
                WeightBand band = new WeightBand();
                band.setFromKg(number(row, 1, p + ".fromKg", BANDS, BAND_HEADERS));
                band.setToKg(number(row, 2, p + ".toKg", BANDS, BAND_HEADERS));
                band.setPrice(number(row, 3, p + ".price", BANDS, BAND_HEADERS));
                route.getWeightBands().add(band);
            }
        }

        private void readAreas(FreightTableContent content) {
            List<SpecialArea> areas = new ArrayList<>();
            for (Row row : dataRows(AREAS)) {
                String p = "specialAreas[" + areas.size() + "]";
                SpecialArea area = new SpecialArea();
                String type = text(row, 0);
                remember(p + ".type", AREAS, row, 0, AREA_HEADERS);
                remember(p, AREAS, row, 0, AREA_HEADERS);
                if (type != null) {
                    try {
                        area.setType(SpecialAreaType.valueOf(type.trim().toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        error(AREAS, row, 0, AREA_HEADERS, "Tipo de área inválido: '" + type + "' (use TDA ou TDE).");
                    }
                }
                area.setCepFrom(cep(row, 1, p + ".cepFrom", AREAS, AREA_HEADERS));
                area.setCepTo(cep(row, 2, p + ".cepTo", AREAS, AREA_HEADERS));
                area.setPercentOverFreightWeight(number(row, 3, p + ".percentOverFreightWeight", AREAS, AREA_HEADERS));
                area.setMinimum(number(row, 4, p + ".minimum", AREAS, AREA_HEADERS));
                areas.add(area);
            }
            content.setSpecialAreas(areas);
        }

        // ---- leitura de células

        private List<Row> dataRows(String sheetName) {
            Sheet sheet = wb.findSheet(sheetName).orElse(null);
            if (sheet == null) {
                errors.add(new Located(sheetName, "Aba '" + sheetName + "' não encontrada na planilha."));
                return List.of();
            }
            List<Row> rows = new ArrayList<>();
            try {
                for (Row row : sheet.read()) {
                    if (row.getRowNum() > 1 && !isEmpty(row)) {
                        rows.add(row);
                    }
                }
            } catch (IOException e) {
                throw new UnreadableSpreadsheetException("Não foi possível ler a aba " + sheetName, e);
            }
            return rows;
        }

        private static boolean isEmpty(Row row) {
            for (Cell cell : row) {
                if (cell != null && cell.getType() != CellType.EMPTY && !cell.getText().isBlank()) {
                    return false;
                }
            }
            return true;
        }

        private static String text(Row row, int col) {
            Cell cell = cell(row, col);
            if (cell == null || cell.getType() == CellType.EMPTY) {
                return null;
            }
            String text = cell.getType() == CellType.NUMBER ? cell.asNumber().stripTrailingZeros().toPlainString()
                    : cell.asString();
            return text == null || text.isBlank() ? null : text.trim();
        }

        private static Cell cell(Row row, int col) {
            return row.hasCell(col) ? row.getCell(col) : null;
        }

        private BigDecimal number(Row row, int col, String path, String sheet, String[] headers) {
            remember(path, sheet, row, col, headers);
            Cell cell = cell(row, col);
            if (cell == null || cell.getType() == CellType.EMPTY || cell.getText().isBlank()) {
                return null;
            }
            if (cell.getType() == CellType.NUMBER) {
                return cell.asNumber();
            }
            String raw = cell.getText().trim().replace(" ", "");
            try {
                // aceita vírgula decimal ("1,20") e ponto de milhar ("1.234,50")
                String normalized = raw.contains(",") ? raw.replace(".", "").replace(',', '.') : raw;
                return new BigDecimal(normalized);
            } catch (NumberFormatException e) {
                error(sheet, row, col, headers, "Valor numérico inválido: '" + raw + "'.");
                return null;
            }
        }

        private String cep(Row row, int col, String path, String sheet, String[] headers) {
            remember(path, sheet, row, col, headers);
            Cell cell = cell(row, col);
            if (cell == null || cell.getType() == CellType.EMPTY || cell.getText().isBlank()) {
                return null;
            }
            if (cell.getType() == CellType.NUMBER) {
                // o Excel guarda CEP numérico sem o zero à esquerda
                String digits = cell.asNumber().toBigInteger().toString();
                return digits.length() < 8 ? "0".repeat(8 - digits.length()) + digits : digits;
            }
            return cell.getText().trim().replace("-", "").replace(".", "");
        }

        private LocalDate date(Row row, int col, String path, String sheet, String[] headers) {
            remember(path, sheet, row, col, headers);
            Cell cell = cell(row, col);
            if (cell == null || cell.getType() == CellType.EMPTY || cell.getText().isBlank()) {
                return null;
            }
            try {
                if (cell.getType() == CellType.NUMBER) {
                    return cell.asDate().toLocalDate();
                }
                String raw = cell.getText().trim();
                return raw.contains("/") ? LocalDate.parse(raw, DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                        : LocalDate.parse(raw);
            } catch (RuntimeException e) {
                error(sheet, row, col, headers, "Data inválida: '" + cell.getText() + "' (use AAAA-MM-DD ou DD/MM/AAAA).");
                return null;
            }
        }

        private Boolean bool(Row row, int col, String path, String sheet, String[] headers) {
            remember(path, sheet, row, col, headers);
            Cell cell = cell(row, col);
            if (cell == null || cell.getType() == CellType.EMPTY || cell.getText().isBlank()) {
                return null;
            }
            if (cell.getType() == CellType.BOOLEAN) {
                return cell.asBoolean();
            }
            return switch (cell.getText().trim().toUpperCase(Locale.ROOT)) {
                case "SIM", "S", "TRUE", "VERDADEIRO", "1" -> Boolean.TRUE;
                case "NAO", "NÃO", "N", "FALSE", "FALSO", "0" -> Boolean.FALSE;
                default -> {
                    error(sheet, row, col, headers, "Valor inválido: '" + cell.getText() + "' (use SIM ou NAO).");
                    yield null;
                }
            };
        }

        // ---- localização

        private void remember(String path, String sheet, Row row, int col, String[] headers) {
            remember(path, sheet, row.getRowNum(), col, headers);
        }

        private void remember(String path, String sheet, int rowNum, int col, String[] headers) {
            cells.put(path, new String[] {sheet, String.valueOf(rowNum), letter(col), headers[col]});
        }

        private void error(String sheet, Row row, int col, String[] headers, String reason) {
            errors.add(describe(new String[] {sheet, String.valueOf(row.getRowNum()), letter(col), headers[col]}, reason));
        }

        /** Traduz o caminho de um erro de validação para aba/linha/coluna; sem correspondência, mantém o caminho. */
        private Located locate(String path, String message) {
            String current = path;
            while (true) {
                String[] cell = cells.get(current);
                if (cell != null) {
                    return describe(cell, message);
                }
                int cut = Math.max(current.lastIndexOf('.'), current.lastIndexOf('['));
                if (cut <= 0) {
                    return new Located(path.isEmpty() ? "planilha" : path, message);
                }
                current = current.substring(0, cut);
            }
        }
    }

    static Located describe(String[] cell, String reason) {
        return new Located(cell[0] + "!" + cell[2] + cell[1],
                "Aba " + cell[0] + ", linha " + cell[1] + ", coluna " + cell[2] + " (" + cell[3] + "): " + reason);
    }

    static String letter(int zeroBased) {
        StringBuilder sb = new StringBuilder();
        int n = zeroBased;
        do {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }
}

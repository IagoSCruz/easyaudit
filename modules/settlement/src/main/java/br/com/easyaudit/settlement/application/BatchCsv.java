package br.com.easyaudit.settlement.application;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import br.com.easyaudit.settlement.application.BatchModel.Batch;
import br.com.easyaudit.settlement.application.BatchModel.Item;
import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;

/**
 * CSV do lote para o financeiro/ERP: UTF-8 com BOM, separador {@code ;}, decimais com vírgula, datas
 * {@code dd/MM/yyyy}, uma linha por CT-e. Campos de texto livre que começam como fórmula de planilha recebem um
 * apóstrofo na frente.
 */
final class BatchCsv {

    static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    static final List<String> HEADER = List.of("lote", "cnpj_transportadora", "transportadora", "chave_cte",
            "numero_cte", "serie_cte", "data_emissao", "valor_cobrado", "valor_aprovado", "glosa", "vencimento");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private BatchCsv() {
    }

    static byte[] render(Batch batch, List<Item> items) {
        StringWriter out = new StringWriter();
        try (CsvWriter csv = CsvWriter.builder().fieldSeparator(';').lineDelimiter(LineDelimiter.CRLF).build(out)) {
            csv.writeRecord(HEADER);
            for (Item item : items) {
                csv.writeRecord(batch.id().toString(), item.carrierCnpj(), safe(item.carrierName()),
                        item.cteAccessKey(), item.number(), item.series(), date(item.emissionDate()),
                        money(item.chargedAmount()), money(item.approvedAmount()), money(item.savingsAmount()),
                        date(batch.dueDate()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[BOM.length + body.length];
        System.arraycopy(BOM, 0, result, 0, BOM.length);
        System.arraycopy(body, 0, result, BOM.length, body.length);
        return result;
    }

    static String money(BigDecimal value) {
        return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    private static String date(LocalDate date) {
        return date == null ? "" : DATE.format(date);
    }

    private static String safe(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + text : text;
    }
}

package br.com.easyaudit.audit.infrastructure.web;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.application.CaseQueries;
import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.contracts.api.model.CaseStatus;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.TenantId;
import de.siegmar.fastcsv.writer.CsvWriter;
import de.siegmar.fastcsv.writer.LineDelimiter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Exportação CSV da listagem (UTF-8 com BOM, separador {@code ;}, decimais com vírgula). Não implementa a interface
 * gerada de propósito: o corpo é escrito em streaming (a interface gerada devolve um {@code Resource} já carregado).
 * O {@code count} prévio e o limite de linhas são conferidos antes de a resposta começar, então o 422 ainda é possível.
 */
@RestController
public class AuditExportController {

    static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    static final List<String> HEADER = List.of("numero_cte", "serie_cte", "chave_cte", "cnpj_transportadora",
            "transportadora", "data_emissao", "uf_destino", "estado", "valor_cobrado", "valor_referencia", "divergencia",
            "valor_aprovado", "verificacoes_com_falha", "alertas");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final CaseQueries queries;

    public AuditExportController(CaseQueries queries) {
        this.queries = queries;
    }

    @GetMapping(value = "/api/v1/audits/export", produces = "text/csv")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<StreamingResponseBody> export(
            @RequestParam(value = "status", required = false) List<CaseStatus> status,
            @RequestParam(value = "carrierId", required = false) UUID carrierId,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "destinationUf", required = false) String destinationUf,
            @RequestParam(value = "failedCheck", required = false) String failedCheck,
            @RequestParam(value = "minDivergence", required = false) BigDecimal minDivergence,
            @RequestParam(value = "alert", required = false) String alert,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sort", required = false, defaultValue = "emissionDate") String sort,
            @RequestParam(value = "direction", required = false, defaultValue = "desc") String direction) {
        var filter = AuditQueriesController.filter(status, carrierId, from, to, destinationUf, failedCheck, minDivergence, alert, q);
        queries.checkExportable(filter);
        TenantId tenant = TenantContext.require();
        // o corpo roda em outra thread: o tenant (e a transação) precisam ser restabelecidos aqui
        StreamingResponseBody body = out -> {
            try {
                TenantContext.runAs(tenant, () -> {
                    try {
                        write(out, filter, sort, direction);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        };
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("casos-de-auditoria.csv").build().toString())
                .body(body);
    }

    private void write(OutputStream out, br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository.Filter filter,
            String sort, String direction) throws java.io.IOException {
        out.write(BOM);
        var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        try (CsvWriter csv = CsvWriter.builder().fieldSeparator(';').lineDelimiter(LineDelimiter.CRLF).build(writer)) {
            csv.writeRecord(HEADER);
            queries.stream(filter, sort, direction, c -> row(csv, c));
        }
    }

    private static void row(CsvWriter csv, StoredCase c) {
        csv.writeRecord(c.cteAccessKey().substring(25, 34), c.cteAccessKey().substring(22, 25), c.cteAccessKey(),
                c.carrierCnpj(), safe(c.carrierName()), c.emissionDate() == null ? "" : DATE.format(c.emissionDate()),
                c.destinationUf() == null ? "" : c.destinationUf(), c.status().name(), money(c.chargedAmount()),
                money(c.referenceAmount()), money(c.divergenceAmount()), money(c.approvedAmount()),
                String.join(",", c.failedChecks()), String.join(",", c.alerts()));
    }

    private static String money(BigDecimal value) {
        return value == null ? "" : value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    /** Texto livre que começa como fórmula de planilha recebe um apóstrofo na frente. */
    private static String safe(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r' ? "'" + text : text;
    }
}

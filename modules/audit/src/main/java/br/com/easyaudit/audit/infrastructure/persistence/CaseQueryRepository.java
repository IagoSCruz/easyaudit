package br.com.easyaudit.audit.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import br.com.easyaudit.audit.domain.StoredCase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Consultas do dashboard em SQL direto sobre {@code audit.audit_case} (colunas desnormalizadas e índices do design),
 * protegidas pela RLS: tudo roda na transação do tenant.
 */
@Repository
public class CaseQueryRepository {

    /** Filtros da listagem e da exportação. Campos nulos (ou lista vazia) não filtram. */
    public record Filter(List<String> statuses, UUID carrierId, LocalDate from, LocalDate to, String destinationUf,
            String failedCheck, BigDecimal minDivergence, String alert, String query) {
    }

    public enum SortField {
        EMISSION_DATE("emission_date"), CHARGED_AMOUNT("charged_amount"), DIVERGENCE_AMOUNT("divergence_amount");

        final String column;

        SortField(String column) {
            this.column = column;
        }
    }

    public record Revision(int revision, String trigger, String classification, BigDecimal chargedAmount,
            BigDecimal referenceAmount, OffsetDateTime createdAt, String snapshot) {
    }

    public record Transition(String fromStatus, String toStatus, String actor, String action, String note,
            BigDecimal approvedAmount, OffsetDateTime createdAt) {
    }

    public record Dispute(String category, String description, BigDecimal amountDue, String status,
            BigDecimal agreedAmount, String resolutionNote, String createdBy, OffsetDateTime createdAt,
            OffsetDateTime resolvedAt, Integer revision) {
    }

    public record StatusTotal(String status, long count, BigDecimal charged) {
    }

    public record Totals(long cases, BigDecimal charged, BigDecimal reference, BigDecimal divergence,
            BigDecimal approved, BigDecimal chargedOfApproved, long compliant, long divergent) {
    }

    public record CarrierRow(UUID carrierId, String carrierCnpj, String carrierName, long cases, BigDecimal charged,
            long divergentCount, BigDecimal divergence, long compliant, BigDecimal chargedApproved, BigDecimal approved) {
    }

    private static final String APPROVED_STATUSES = "('APPROVED', 'SCHEDULED_FOR_PAYMENT', 'PAID')";

    private final JdbcClient jdbc;
    private final JdbcTemplate streaming;

    public CaseQueryRepository(JdbcClient jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        // leitura em blocos para a exportação: não carrega o resultado inteiro na memória
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(500);
    }

    // ---- listagem e exportação

    public List<StoredCase> list(Filter filter, SortField sort, boolean ascending, int limit, long offset) {
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params);
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql("SELECT " + CaseRepository.COLUMNS + " FROM audit.audit_case WHERE " + where + order(sort, ascending)
                        + " LIMIT :limit OFFSET :offset")
                .params(params).query((rs, n) -> CaseRepository.map(rs)).list();
    }

    public long count(Filter filter) {
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params);
        return jdbc.sql("SELECT count(*) FROM audit.audit_case WHERE " + where).params(params).query(Long.class).single();
    }

    /** Percorre os casos do filtro em blocos, na ordem pedida, sem carregar tudo na memória. */
    public void stream(Filter filter, SortField sort, boolean ascending, java.util.function.Consumer<StoredCase> consumer) {
        Map<String, Object> params = new HashMap<>();
        String where = where(filter, params);
        // JdbcTemplate com parâmetros nomeados: converte para posicionais
        var named = new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(streaming);
        named.query("SELECT " + CaseRepository.COLUMNS + " FROM audit.audit_case WHERE " + where + order(sort, ascending),
                params, (RowCallbackHandler) rs -> consumer.accept(CaseRepository.map(rs)));
    }

    private static String order(SortField sort, boolean ascending) {
        return " ORDER BY " + sort.column + (ascending ? " ASC" : " DESC") + " NULLS LAST, id";
    }

    static String where(Filter f, Map<String, Object> params) {
        List<String> parts = new ArrayList<>();
        parts.add("TRUE");
        if (f.statuses() != null && !f.statuses().isEmpty()) {
            parts.add("status = ANY(:statuses)");
            params.put("statuses", f.statuses().toArray(String[]::new));
        }
        if (f.carrierId() != null) {
            parts.add("carrier_id = :carrierId");
            params.put("carrierId", f.carrierId());
        }
        if (f.from() != null) {
            parts.add("emission_date >= :from");
            params.put("from", f.from());
        }
        if (f.to() != null) {
            parts.add("emission_date <= :to");
            params.put("to", f.to());
        }
        if (f.destinationUf() != null && !f.destinationUf().isBlank()) {
            parts.add("destination_uf = :uf");
            params.put("uf", f.destinationUf().trim().toUpperCase());
        }
        if (f.failedCheck() != null && !f.failedCheck().isBlank()) {
            parts.add("failed_checks @> ARRAY[CAST(:failedCheck AS text)]");
            params.put("failedCheck", f.failedCheck().trim());
        }
        if (f.minDivergence() != null) {
            parts.add("divergence_amount >= :minDivergence");
            params.put("minDivergence", f.minDivergence());
        }
        if (f.alert() != null && !f.alert().isBlank()) {
            parts.add("alerts @> ARRAY[CAST(:alert AS text)]");
            params.put("alert", f.alert().trim());
        }
        if (f.query() != null && !f.query().isBlank()) {
            String q = f.query().trim();
            if (q.length() == 44) {
                parts.add("cte_access_key = :queryKey");
                params.put("queryKey", q);
            } else if (q.length() <= 9 && q.chars().allMatch(Character::isDigit)) {
                parts.add("substr(cte_access_key::text, 26, 9) = lpad(:queryNumber, 9, '0')");
                params.put("queryNumber", q);
            } else {
                parts.add("FALSE");
            }
        }
        return String.join(" AND ", parts);
    }

    // ---- detalhe

    public List<Revision> revisions(UUID caseId) {
        return jdbc.sql("""
                SELECT revision, trigger, classification, charged_amount, reference_amount, created_at, snapshot::text AS snapshot
                FROM audit.audit_revision WHERE case_id = :case ORDER BY revision DESC""")
                .param("case", caseId).query((rs, n) -> new Revision(rs.getInt("revision"), rs.getString("trigger"),
                        rs.getString("classification"), rs.getBigDecimal("charged_amount"), rs.getBigDecimal("reference_amount"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getString("snapshot"))).list();
    }

    public List<Transition> transitions(UUID caseId) {
        return jdbc.sql("""
                SELECT from_status, to_status, actor, action, note, approved_amount, created_at
                FROM audit.case_transition WHERE case_id = :case ORDER BY created_at, id""")
                .param("case", caseId).query((rs, n) -> new Transition(rs.getString("from_status"), rs.getString("to_status"),
                        rs.getString("actor"), rs.getString("action"), rs.getString("note"), rs.getBigDecimal("approved_amount"),
                        rs.getObject("created_at", OffsetDateTime.class))).list();
    }

    /** A contestação mais recente do caso, com a revisão vigente quando foi aberta. */
    public Optional<Dispute> latestDispute(UUID caseId) {
        return jdbc.sql("""
                SELECT d.category, d.description, d.amount_due, d.status, d.agreed_amount, d.resolution_note, d.created_by,
                       d.created_at, d.resolved_at,
                       (SELECT max(r.revision) FROM audit.audit_revision r
                        WHERE r.case_id = d.case_id AND r.created_at <= d.created_at) AS revision
                FROM audit.dispute d WHERE d.case_id = :case ORDER BY d.created_at DESC LIMIT 1""")
                .param("case", caseId).query((rs, n) -> new Dispute(rs.getString("category"), rs.getString("description"),
                        rs.getBigDecimal("amount_due"), rs.getString("status"), rs.getBigDecimal("agreed_amount"),
                        rs.getString("resolution_note"), rs.getString("created_by"),
                        rs.getObject("created_at", OffsetDateTime.class), rs.getObject("resolved_at", OffsetDateTime.class),
                        (Integer) rs.getObject("revision"))).optional();
    }

    // ---- indicadores

    public List<StatusTotal> totalsByStatus(LocalDate from, LocalDate to, UUID carrierId) {
        Map<String, Object> params = new HashMap<>();
        String where = period(from, to, carrierId, params);
        return jdbc.sql("SELECT status, count(*) AS n, COALESCE(sum(charged_amount), 0) AS charged"
                        + " FROM audit.audit_case WHERE " + where + " GROUP BY status")
                .params(params).query((rs, n) -> new StatusTotal(rs.getString("status"), rs.getLong("n"),
                        rs.getBigDecimal("charged"))).list();
    }

    public Totals totals(LocalDate from, LocalDate to, UUID carrierId) {
        Map<String, Object> params = new HashMap<>();
        String where = period(from, to, carrierId, params);
        return jdbc.sql("SELECT"
                        + " count(*) FILTER (WHERE status <> 'CANCELLED') AS cases,"
                        + " COALESCE(sum(charged_amount) FILTER (WHERE status <> 'CANCELLED'), 0) AS charged,"
                        + " COALESCE(sum(reference_amount) FILTER (WHERE status <> 'CANCELLED'), 0) AS reference,"
                        + " COALESCE(sum(divergence_amount) FILTER (WHERE status = 'DIVERGENT'), 0) AS divergence,"
                        + " COALESCE(sum(approved_amount) FILTER (WHERE status IN " + APPROVED_STATUSES + "), 0) AS approved,"
                        + " COALESCE(sum(charged_amount) FILTER (WHERE status IN " + APPROVED_STATUSES + "), 0) AS charged_approved,"
                        + " count(*) FILTER (WHERE status <> 'CANCELLED' AND last_classification = 'COMPLIANT') AS compliant,"
                        + " count(*) FILTER (WHERE status <> 'CANCELLED' AND last_classification = 'DIVERGENT') AS divergent"
                        + " FROM audit.audit_case WHERE " + where)
                .params(params).query((rs, n) -> new Totals(rs.getLong("cases"), rs.getBigDecimal("charged"),
                        rs.getBigDecimal("reference"), rs.getBigDecimal("divergence"), rs.getBigDecimal("approved"),
                        rs.getBigDecimal("charged_approved"), rs.getLong("compliant"), rs.getLong("divergent"))).single();
    }

    /** Uma linha por transportadora (CNPJ completo), da maior para a menor divergência. */
    public List<CarrierRow> carrierRanking(LocalDate from, LocalDate to) {
        Map<String, Object> params = new HashMap<>();
        String where = period(from, to, null, params);
        return jdbc.sql("SELECT carrier_cnpj,"
                        + " (array_agg(carrier_id) FILTER (WHERE carrier_id IS NOT NULL))[1] AS carrier_id,"
                        + " max(carrier_name) AS carrier_name,"
                        + " count(*) AS cases, COALESCE(sum(charged_amount), 0) AS charged,"
                        + " count(*) FILTER (WHERE last_classification = 'DIVERGENT') AS divergent_count,"
                        + " COALESCE(sum(divergence_amount) FILTER (WHERE last_classification = 'DIVERGENT'), 0) AS divergence,"
                        + " count(*) FILTER (WHERE last_classification = 'COMPLIANT') AS compliant,"
                        + " COALESCE(sum(charged_amount) FILTER (WHERE status IN " + APPROVED_STATUSES + "), 0) AS charged_approved,"
                        + " COALESCE(sum(approved_amount) FILTER (WHERE status IN " + APPROVED_STATUSES + "), 0) AS approved"
                        + " FROM audit.audit_case WHERE status <> 'CANCELLED' AND " + where
                        + " GROUP BY carrier_cnpj ORDER BY divergence DESC, carrier_cnpj")
                .params(params).query((rs, n) -> new CarrierRow(rs.getObject("carrier_id", UUID.class),
                        rs.getString("carrier_cnpj").trim(), rs.getString("carrier_name"), rs.getLong("cases"),
                        rs.getBigDecimal("charged"), rs.getLong("divergent_count"), rs.getBigDecimal("divergence"),
                        rs.getLong("compliant"), rs.getBigDecimal("charged_approved"), rs.getBigDecimal("approved"))).list();
    }

    private static String period(LocalDate from, LocalDate to, UUID carrierId, Map<String, Object> params) {
        return where(new Filter(null, carrierId, from, to, null, null, null, null, null), params);
    }
}

package br.com.easyaudit.audit.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import br.com.easyaudit.audit.domain.CaseStatus;
import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository;
import br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository.CarrierRow;
import br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository.Dispute;
import br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository.Filter;
import br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository.Revision;
import br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository.SortField;
import br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository.StatusTotal;
import br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository.Totals;
import br.com.easyaudit.audit.infrastructure.persistence.CaseQueryRepository.Transition;
import br.com.easyaudit.audit.infrastructure.persistence.CaseRepository;
import br.com.easyaudit.contracts.api.model.CanonicalCte;
import br.com.easyaudit.contracts.api.model.CanonicalNfe;
import br.com.easyaudit.documents.api.DocumentCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Consultas do dashboard: listagem, detalhe, indicadores, ranking e exportação. */
@Service
public class CaseQueries {

    public static final int MAX_PAGE_SIZE = 100;

    public record Page(List<StoredCase> items, int page, int size, long totalElements) {

        public int totalPages() {
            return (int) Math.ceil(totalElements / (double) size);
        }
    }

    public record RevisionView(Revision revision, Map<String, Object> snapshot) {
    }

    public record Detail(StoredCase caseRow, CanonicalCte cte, List<CanonicalNfe> linkedNfes, List<String> pendingNfeKeys,
            Map<String, Object> quote, RevisionView currentRevision, List<Revision> previousRevisions,
            List<Transition> history, Dispute dispute) {
    }

    public record Summary(Map<CaseStatus, StatusTotal> byStatus, Totals totals, BigDecimal savings,
            BigDecimal conformanceRate) {
    }

    public record RankingRow(CarrierRow row, BigDecimal divergenceRate, BigDecimal savings) {
    }

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final CaseQueryRepository queries;
    private final CaseRepository cases;
    private final DocumentCatalog documents;
    private final JsonMapper mapper;
    private final int exportMaxRows;

    public CaseQueries(CaseQueryRepository queries, CaseRepository cases, DocumentCatalog documents, JsonMapper mapper,
            @Value("${easyaudit.audit.export.max-rows:50000}") int exportMaxRows) {
        this.queries = queries;
        this.cases = cases;
        this.documents = documents;
        this.mapper = mapper;
        this.exportMaxRows = exportMaxRows;
    }

    public int exportMaxRows() {
        return exportMaxRows;
    }

    // ---- listagem

    @Transactional(readOnly = true)
    public Page list(Filter filter, String sort, String direction, int page, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw AuditException.badRequest("PAGE_SIZE_EXCEEDED",
                    "O tamanho da página deve ser de 1 a " + MAX_PAGE_SIZE + " (pedido: " + size + ").");
        }
        if (page < 0) {
            throw AuditException.badRequest("INVALID_PAGE", "A página deve ser zero ou maior.");
        }
        validateFilter(filter);
        return new Page(queries.list(filter, sortField(sort), ascending(direction), size, (long) page * size), page, size,
                queries.count(filter));
    }

    /** Valida o filtro de exportação e confere o limite de linhas antes de começar a escrever. */
    @Transactional(readOnly = true)
    public long checkExportable(Filter filter) {
        validateFilter(filter);
        long total = queries.count(filter);
        if (total > exportMaxRows) {
            throw AuditException.invalid("EXPORT_TOO_LARGE", "O filtro retorna " + total + " casos e o limite da exportação é de "
                    + exportMaxRows + " linhas. Use um filtro mais restrito.");
        }
        return total;
    }

    /** Percorre os casos do filtro para a exportação; chamar dentro de uma transação do tenant. */
    @Transactional(readOnly = true)
    public void stream(Filter filter, String sort, String direction, Consumer<StoredCase> consumer) {
        queries.stream(filter, sortField(sort), ascending(direction), consumer);
    }

    private static void validateFilter(Filter filter) {
        if (filter.statuses() != null) {
            for (String s : filter.statuses()) {
                try {
                    CaseStatus.valueOf(s);
                } catch (IllegalArgumentException e) {
                    throw AuditException.badRequest("INVALID_STATUS", "Estado desconhecido: " + s);
                }
            }
        }
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw AuditException.badRequest("INVALID_PERIOD", "O início do período é posterior ao fim.");
        }
    }

    private static SortField sortField(String sort) {
        return switch (sort == null ? "emissionDate" : sort) {
            case "emissionDate" -> SortField.EMISSION_DATE;
            case "chargedAmount" -> SortField.CHARGED_AMOUNT;
            case "divergenceAmount" -> SortField.DIVERGENCE_AMOUNT;
            default -> throw AuditException.badRequest("INVALID_SORT", "Ordenação desconhecida: " + sort
                    + " (use emissionDate, chargedAmount ou divergenceAmount).");
        };
    }

    private static boolean ascending(String direction) {
        if (direction == null || "desc".equalsIgnoreCase(direction)) {
            return false;
        }
        if ("asc".equalsIgnoreCase(direction)) {
            return true;
        }
        throw AuditException.badRequest("INVALID_SORT", "Direção desconhecida: " + direction + " (use asc ou desc).");
    }

    // ---- detalhe

    @Transactional(readOnly = true)
    public Detail detail(UUID caseId) {
        StoredCase c = cases.findById(caseId).orElseThrow(() -> AuditException.notFound("Caso de auditoria não encontrado."));
        var cteDoc = documents.find(c.cteDocumentId()).orElse(null);
        List<CanonicalNfe> linked = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (var link : documents.linksFrom(c.cteDocumentId())) {
            if (link.type() != DocumentCatalog.LinkType.CTE_NFE) {
                continue;
            }
            var nfe = link.resolved() && link.targetDocumentId() != null ? documents.find(link.targetDocumentId()).orElse(null) : null;
            if (nfe != null && nfe.nfe() != null) {
                linked.add(nfe.nfe());
            } else {
                pending.add(link.targetKey());
            }
        }
        List<Revision> revisions = queries.revisions(caseId);
        RevisionView current = null;
        List<Revision> previous = new ArrayList<>();
        for (Revision r : revisions) {
            if (r.revision() == c.currentRevision() && current == null) {
                current = new RevisionView(r, parse(r.snapshot()));
            } else {
                previous.add(r);
            }
        }
        Map<String, Object> quote = null;
        if (current != null && current.snapshot().get("input") instanceof Map<?, ?> input
                && input.get("quote") instanceof Map<?, ?> q) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) q;
            quote = typed;
        }
        return new Detail(c, cteDoc == null ? null : cteDoc.cte(), linked, pending, quote, current, previous,
                queries.transitions(caseId), queries.latestDispute(caseId).orElse(null));
    }

    private Map<String, Object> parse(String json) {
        return mapper.readValue(json, MAP);
    }

    // ---- indicadores

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to, UUID carrierId) {
        if (from != null && to != null && from.isAfter(to)) {
            throw AuditException.badRequest("INVALID_PERIOD", "O início do período é posterior ao fim.");
        }
        Map<CaseStatus, StatusTotal> byStatus = new EnumMap<>(CaseStatus.class);
        for (CaseStatus s : CaseStatus.values()) {
            byStatus.put(s, new StatusTotal(s.name(), 0, BigDecimal.ZERO));
        }
        queries.totalsByStatus(from, to, carrierId).forEach(t -> byStatus.put(CaseStatus.valueOf(t.status()), t));
        Totals totals = queries.totals(from, to, carrierId);
        BigDecimal savings = totals.chargedOfApproved().subtract(totals.approved());
        return new Summary(byStatus, totals, savings, rate(totals.compliant(), totals.compliant() + totals.divergent()));
    }

    @Transactional(readOnly = true)
    public List<RankingRow> ranking(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw AuditException.badRequest("INVALID_PERIOD", "O início do período é posterior ao fim.");
        }
        return queries.carrierRanking(from, to).stream().map(r -> new RankingRow(r,
                rate(r.divergentCount(), r.divergentCount() + r.compliant()), r.chargedApproved().subtract(r.approved()))).toList();
    }

    /** Percentual com duas casas; nulo quando não há base de cálculo. */
    private static BigDecimal rate(long part, long total) {
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
}

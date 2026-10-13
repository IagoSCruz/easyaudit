package br.com.easyaudit.freight.application;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.UUID;

import br.com.easyaudit.freight.api.FreightTableSelector;
import br.com.easyaudit.freight.domain.CompiledTable;
import br.com.easyaudit.freight.domain.StoredFreightTable;
import br.com.easyaudit.freight.infrastructure.persistence.FreightTableRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seleciona a tabela {@code ACTIVE} da transportadora cujo período contém a data de emissão (fuso America/Sao_Paulo)
 * e, nela, a rota do CEP de destino. As versões ativas são imutáveis, então o conteúdo compilado fica em um cache
 * Caffeine por id da versão; o período de vigência (que muda na ativação de versões novas) é lido do banco.
 */
@Service
public class FreightTableSelection implements FreightTableSelector {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private final FreightTableRepository tables;
    private final FreightTableService service;
    private final Cache<UUID, CompiledTable> cache = Caffeine.newBuilder().maximumSize(500)
            .expireAfterAccess(Duration.ofHours(6)).build();

    public FreightTableSelection(FreightTableRepository tables, FreightTableService service) {
        this.tables = tables;
        this.service = service;
    }

    @Transactional(readOnly = true)
    public TableSelection selectTable(UUID carrierId, OffsetDateTime issuedAt, String destinationCep, String originCep) {
        LocalDate date = issuedAt.atZoneSameInstant(ZONE).toLocalDate();
        StoredFreightTable table = tables.activeOf(carrierId).stream()
                .filter(t -> !date.isBefore(t.validFrom()) && (t.validTo() == null || !date.isAfter(t.validTo())))
                .max(Comparator.comparing(StoredFreightTable::validFrom)).orElse(null);
        if (table == null) {
            return new TableSelection.NoTable();
        }
        CompiledTable compiled = compiled(table);
        return compiled.findRoute(destinationCep, originCep)
                .<TableSelection>map(route -> new TableSelection.Found(table.id(), table.version(), compiled, route))
                .orElseGet(() -> new TableSelection.RouteNotCovered(table.id(), table.version(), compiled));
    }

    @Override
    @Transactional(readOnly = true)
    public Selection select(UUID carrierId, OffsetDateTime issuedAt, String destinationCep, String originCep) {
        return switch (selectTable(carrierId, issuedAt, destinationCep, originCep)) {
            case TableSelection.Found f -> new Selected(f.tableId(), f.tableVersion(), f.route().name());
            case TableSelection.NoTable n -> new NoTable();
            case TableSelection.RouteNotCovered r -> new RouteNotCovered(r.tableId(), r.tableVersion());
        };
    }

    /** Conteúdo compilado da versão; só versões {@code ACTIVE} (imutáveis) ficam no cache. */
    public CompiledTable compiled(StoredFreightTable table) {
        if (table.status() == StoredFreightTable.Status.ACTIVE) {
            return cache.get(table.id(), id -> TableCompiler.compile(service.contentOf(table)));
        }
        return TableCompiler.compile(service.contentOf(table));
    }

    /** Número de versões compiladas em cache (para testes e diagnóstico). */
    public long cachedVersions() {
        return cache.estimatedSize();
    }
}

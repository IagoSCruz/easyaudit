package br.com.easyaudit.freight.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.freight.domain.CalculationResult;
import br.com.easyaudit.freight.domain.CompiledTable;
import br.com.easyaudit.freight.domain.FreightCalculator;
import br.com.easyaudit.freight.domain.ShipmentInput;
import br.com.easyaudit.freight.domain.StoredFreightTable;
import br.com.easyaudit.freight.domain.StoredFreightTable.Status;
import br.com.easyaudit.freight.infrastructure.persistence.CarrierRepository;
import br.com.easyaudit.freight.infrastructure.persistence.FreightTableRepository;
import br.com.easyaudit.freight.infrastructure.xlsx.FreightTableXlsx;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Casos de uso das tabelas de frete: importação (JSON), consulta, edição de rascunho, arquivamento e versões. */
@Service
public class FreightTableService {

    private final FreightTableRepository tables;
    private final CarrierRepository carriers;
    private final JsonMapper mapper;
    private final FreightEventPublisher publisher;

    public FreightTableService(FreightTableRepository tables, CarrierRepository carriers, JsonMapper mapper,
            FreightEventPublisher publisher) {
        this.tables = tables;
        this.carriers = carriers;
        this.mapper = mapper;
        this.publisher = publisher;
    }

    /** Caminhos de campo do JSON de entrada: {@code content.<caminho>}. */
    public static final UnaryOperator<String> JSON_LOCATION = path -> path.isEmpty() ? "content" : "content." + path;

    /**
     * Cria a tabela em {@code DRAFT} com o próximo número de versão da transportadora.
     *
     * @param location traduz o caminho do campo para a localização mostrada ao usuário
     * @throws FreightException           transportadora inexistente (404)
     * @throws TableValidationException   erros de validação (todos juntos)
     */
    @Transactional
    public StoredFreightTable create(UUID carrierId, FreightTableContent content, UnaryOperator<String> location) {
        requireCarrier(carrierId);
        validate(content, location);
        UUID id = tables.insertDraft(carrierId, content.getName().trim(), tables.nextVersion(carrierId),
                content.getValidFrom(), content.getValidTo(), content.getIncludesIcms(), json(content));
        return get(id);
    }

    /**
     * Importa uma planilha XLSX no formato do modelo, criando o rascunho. Os erros de leitura das células e os da
     * validação saem juntos, cada um com aba, linha e coluna.
     *
     * @throws FreightException           transportadora inexistente (404) ou planilha ilegível (422)
     * @throws TableValidationException   erros na planilha
     */
    @Transactional
    public StoredFreightTable importXlsx(UUID carrierId, byte[] xlsx) {
        requireCarrier(carrierId);
        FreightTableXlsx.Parsed parsed;
        try {
            parsed = FreightTableXlsx.parse(xlsx);
        } catch (FreightTableXlsx.UnreadableSpreadsheetException e) {
            throw FreightException.invalid("INVALID_SPREADSHEET", e.getMessage());
        }
        List<TableValidationException.Located> errors = new ArrayList<>(parsed.readErrors());
        FreightTableValidator.validate(parsed.content())
                .forEach(i -> errors.add(parsed.locate().apply(i.path(), i.message())));
        if (!errors.isEmpty()) {
            throw new TableValidationException(errors);
        }
        FreightTableContent content = parsed.content();
        UUID id = tables.insertDraft(carrierId, content.getName().trim(), tables.nextVersion(carrierId),
                content.getValidFrom(), content.getValidTo(), content.getIncludesIcms(), json(content));
        return get(id);
    }

    /**
     * Substitui o conteúdo de um rascunho. @throws FreightException tabela inexistente (404) ou fora de
     * {@code DRAFT} (409: uma tabela {@code ACTIVE} é imutável)
     */
    @Transactional
    public StoredFreightTable update(UUID id, FreightTableContent content) {
        StoredFreightTable current = requireDraft(id, "editada");
        validate(content, JSON_LOCATION);
        tables.updateDraft(current.id(), content.getName().trim(), content.getValidFrom(), content.getValidTo(),
                content.getIncludesIcms(), json(content));
        return get(id);
    }

    /** Arquiva um rascunho. @throws FreightException 404, ou 409 se a tabela não estiver em {@code DRAFT} */
    @Transactional
    public StoredFreightTable archive(UUID id) {
        requireDraft(id, "arquivada");
        tables.archiveDraft(id);
        return get(id);
    }

    /**
     * Cria um rascunho com o mesmo conteúdo da tabela e o próximo número de versão da transportadora.
     *
     * @throws FreightException 404, ou 409 se a tabela já é um rascunho (edite-a em vez de criar outra versão)
     */
    @Transactional
    public StoredFreightTable newVersion(UUID id) {
        StoredFreightTable source = get(id);
        if (source.status() == Status.DRAFT) {
            throw FreightException.conflict("TABLE_IS_DRAFT",
                    "A tabela já é um rascunho; edite-a em vez de criar uma nova versão.");
        }
        UUID created = tables.insertDraft(source.carrierId(), source.name(), tables.nextVersion(source.carrierId()),
                source.validFrom(), source.validTo(), source.includesIcms(), source.contentJson());
        return get(created);
    }

    /**
     * Ativa um rascunho. As ativações da mesma transportadora são serializadas por um lock nas versões dela. O
     * início da vigência precisa ser posterior ao da última versão ativa; se a versão anterior não tinha fim ou
     * tinha um fim posterior, o fim dela passa para o dia anterior ao início da nova. Publica
     * {@code freight-table.activated}.
     *
     * @throws FreightException 404; 409 se não está em {@code DRAFT} ou se a vigência é conflitante (nada muda)
     */
    @Transactional
    public StoredFreightTable activate(UUID id) {
        StoredFreightTable table = get(id);
        List<StoredFreightTable> locked = tables.lockCarrierTables(table.carrierId());
        // relê depois do lock: outra ativação pode ter acabado de mudar o estado
        StoredFreightTable current = locked.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
        if (current.status() != Status.DRAFT) {
            throw FreightException.conflict("TABLE_NOT_DRAFT", "Só uma tabela em DRAFT pode ser ativada; esta está "
                    + current.status() + ".");
        }
        StoredFreightTable latestActive = locked.stream().filter(t -> t.status() == Status.ACTIVE)
                .max(java.util.Comparator.comparing(StoredFreightTable::validFrom)).orElse(null);
        if (latestActive != null && !current.validFrom().isAfter(latestActive.validFrom())) {
            throw FreightException.conflict("ACTIVATION_NOT_AFTER_LATEST_VERSION",
                    "O início da vigência (" + current.validFrom() + ") precisa ser posterior ao início da última versão"
                            + " ativa (v" + latestActive.version() + ", " + latestActive.validFrom() + ").");
        }
        if (latestActive != null && (latestActive.validTo() == null || !latestActive.validTo().isBefore(current.validFrom()))) {
            tables.setValidTo(latestActive.id(), current.validFrom().minusDays(1));
        }
        tables.activate(id);
        publisher.tableActivated(current.carrierId(), id, current.validFrom(), current.validTo());
        return get(id);
    }

    /**
     * Simula o cálculo de um embarque em uma tabela {@code DRAFT} ou {@code ACTIVE}, sem gravar nada.
     *
     * @throws FreightException                          tabela inexistente (404) ou arquivada (409)
     * @throws br.com.easyaudit.freight.domain.InvalidShipmentException entradas inválidas
     */
    @Transactional(readOnly = true)
    public CalculationResult simulate(UUID id, ShipmentInput input) {
        StoredFreightTable table = get(id);
        if (table.status() == Status.ARCHIVED) {
            throw FreightException.conflict("TABLE_ARCHIVED", "Uma tabela arquivada não pode ser simulada.");
        }
        CompiledTable compiled = TableCompiler.compile(contentOf(table));
        return FreightCalculator.calculate(table.id(), table.version(), compiled, input);
    }

    private StoredFreightTable requireDraft(UUID id, String action) {
        StoredFreightTable table = get(id);
        if (table.status() != Status.DRAFT) {
            throw FreightException.conflict("TABLE_NOT_EDITABLE", "Só uma tabela em DRAFT pode ser " + action
                    + "; esta está " + table.status() + ". Crie uma nova versão a partir dela.");
        }
        return table;
    }

    @Transactional(readOnly = true)
    public StoredFreightTable get(UUID id) {
        return tables.findById(id).orElseThrow(() -> FreightException.notFound("Tabela de frete não encontrada."));
    }

    @Transactional(readOnly = true)
    public List<StoredFreightTable> list(UUID carrierId, Status status) {
        return tables.search(carrierId, status);
    }

    public FreightTableContent contentOf(StoredFreightTable table) {
        return mapper.readValue(table.contentJson(), FreightTableContent.class);
    }

    /** Valida o conteúdo; lança {@link TableValidationException} com todos os erros. */
    void validate(FreightTableContent content, UnaryOperator<String> location) {
        List<FreightTableValidator.Issue> issues = FreightTableValidator.validate(content);
        if (!issues.isEmpty()) {
            throw new TableValidationException(issues.stream()
                    .map(i -> new TableValidationException.Located(location.apply(i.path()), i.message())).toList());
        }
    }

    String json(FreightTableContent content) {
        return mapper.writeValueAsString(content);
    }

    private void requireCarrier(UUID carrierId) {
        carriers.findById(carrierId).orElseThrow(() -> FreightException.notFound("Transportadora não encontrada."));
    }
}

package br.com.easyaudit.freight.application;

import java.util.UUID;

import br.com.easyaudit.freight.domain.CompiledTable;

/** Resultado interno da seleção: carrega a tabela compilada e a rota para o cálculo. */
public sealed interface TableSelection {

    record Found(UUID tableId, int tableVersion, CompiledTable table, CompiledTable.Route route)
            implements TableSelection {
    }

    record NoTable() implements TableSelection {
    }

    record RouteNotCovered(UUID tableId, int tableVersion, CompiledTable table) implements TableSelection {
    }
}

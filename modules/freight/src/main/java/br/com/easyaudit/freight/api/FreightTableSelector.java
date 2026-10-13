package br.com.easyaudit.freight.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Seleção da tabela de frete vigente para um embarque. Vale para o tenant do contexto corrente. A data de emissão é
 * avaliada no fuso America/Sao_Paulo.
 */
public interface FreightTableSelector {

    /** Resultado da seleção. */
    sealed interface Selection permits Selected, NoTable, RouteNotCovered {
    }

    /** Há tabela vigente e rota que cobre o destino. */
    record Selected(UUID tableId, int tableVersion, String routeName) implements Selection {
    }

    /** Não há tabela {@code ACTIVE} da transportadora vigente na data de emissão. */
    record NoTable() implements Selection {
    }

    /** Há tabela vigente, mas nenhuma rota cobre o CEP de destino. */
    record RouteNotCovered(UUID tableId, int tableVersion) implements Selection {
    }

    /**
     * @param carrierId      transportadora do tenant
     * @param issuedAt       data de emissão do CT-e
     * @param destinationCep CEP de destino (8 dígitos)
     * @param originCep      CEP de origem (8 dígitos), opcional
     */
    Selection select(UUID carrierId, OffsetDateTime issuedAt, String destinationCep, String originCep);
}

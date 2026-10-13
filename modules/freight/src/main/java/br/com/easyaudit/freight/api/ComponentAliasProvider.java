package br.com.easyaudit.freight.api;

import java.util.Map;

/**
 * Sinônimos de nomes de componente cadastrados para uma transportadora, usados na classificação dos componentes
 * de valor do CT-e. Vale para o tenant do contexto corrente.
 */
public interface ComponentAliasProvider {

    /**
     * @param carrierCnpj CNPJ (14 caracteres, sem máscara) da transportadora emitente
     * @return mapa de nome normalizado (ver {@code ComponentNames.normalize}) para código canônico do componente;
     *         vazio se a transportadora não existe no tenant ou não tem sinônimos
     */
    Map<String, String> aliasesByCarrierCnpj(String carrierCnpj);
}

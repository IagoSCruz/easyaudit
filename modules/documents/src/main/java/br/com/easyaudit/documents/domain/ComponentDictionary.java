package br.com.easyaudit.documents.domain;

import java.util.Map;
import java.util.function.Function;

import br.com.easyaudit.contracts.api.model.ComponentCode;
import br.com.easyaudit.shared.ComponentNames;

/**
 * Dicionário padrão (versionado em código) que classifica o nome livre de um componente de valor do CT-e em um
 * código canônico. As chaves já estão normalizadas (ver {@link ComponentNames#normalize}). Os sinônimos cadastrados
 * pelo tenant para a transportadora prevalecem sobre este dicionário. Ao evoluir o dicionário, incremente
 * {@link #VERSION}.
 */
public final class ComponentDictionary {

    public static final String VERSION = "1";

    private static final Map<String, ComponentCode> DEFAULTS = Map.ofEntries(
            // frete peso
            Map.entry("FRETE PESO", ComponentCode.FREIGHT_WEIGHT),
            Map.entry("FRETE", ComponentCode.FREIGHT_WEIGHT),
            Map.entry("PESO", ComponentCode.FREIGHT_WEIGHT),
            Map.entry("FRETE PESO NORMAL", ComponentCode.FREIGHT_WEIGHT),
            // ad valorem
            Map.entry("AD VALOREM", ComponentCode.AD_VALOREM),
            Map.entry("ADVALOREM", ComponentCode.AD_VALOREM),
            Map.entry("AD VAL", ComponentCode.AD_VALOREM),
            Map.entry("FRETE VALOR", ComponentCode.AD_VALOREM),
            Map.entry("SEGURO", ComponentCode.AD_VALOREM),
            // GRIS
            Map.entry("GRIS", ComponentCode.GRIS),
            Map.entry("TAXA GRIS", ComponentCode.GRIS),
            Map.entry("GERENCIAMENTO DE RISCO", ComponentCode.GRIS),
            // pedágio
            Map.entry("PEDAGIO", ComponentCode.TOLL),
            Map.entry("PEDAGIOS", ComponentCode.TOLL),
            Map.entry("TAXA DE PEDAGIO", ComponentCode.TOLL),
            // TDA e TDE
            Map.entry("TDA", ComponentCode.TDA),
            Map.entry("TAXA TDA", ComponentCode.TDA),
            Map.entry("TAXA DE DIFICIL ACESSO", ComponentCode.TDA),
            Map.entry("TAXA DE DIFICULDADE DE ACESSO", ComponentCode.TDA),
            Map.entry("TDE", ComponentCode.TDE),
            Map.entry("TAXA TDE", ComponentCode.TDE),
            Map.entry("TAXA DE DIFICIL ENTREGA", ComponentCode.TDE),
            Map.entry("TAXA DE DIFICULDADE DE ENTREGA", ComponentCode.TDE),
            // despacho
            Map.entry("DESPACHO", ComponentCode.DISPATCH_FEE),
            Map.entry("TAXA DE DESPACHO", ComponentCode.DISPATCH_FEE),
            Map.entry("TAXA DESPACHO", ComponentCode.DISPATCH_FEE),
            // ICMS
            Map.entry("ICMS", ComponentCode.ICMS));

    private ComponentDictionary() {
    }

    /** Classificação só pelo dicionário padrão; sem correspondência vira {@code OTHER}. */
    public static ComponentCode classify(String name) {
        return DEFAULTS.getOrDefault(ComponentNames.normalize(name), ComponentCode.OTHER);
    }

    /**
     * Classificação com os sinônimos da transportadora (nome normalizado para código), que prevalecem sobre o
     * dicionário padrão.
     */
    public static ComponentCode classify(String name, Map<String, String> carrierAliases) {
        String normalized = ComponentNames.normalize(name);
        String custom = carrierAliases.get(normalized);
        if (custom != null) {
            return ComponentCode.valueOf(custom);
        }
        return DEFAULTS.getOrDefault(normalized, ComponentCode.OTHER);
    }

    /** Versão pronta para o mapeador: cada nome é classificado com os sinônimos já carregados. */
    public static Function<String, ComponentCode> classifier(Map<String, String> carrierAliases) {
        return name -> classify(name, carrierAliases);
    }
}

package br.com.easyaudit.documents.application;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import br.com.easyaudit.contracts.api.model.ComponentCode;
import br.com.easyaudit.documents.domain.ComponentDictionary;
import br.com.easyaudit.freight.api.ComponentAliasProvider;
import org.springframework.stereotype.Service;

/**
 * Classifica componentes de valor com o dicionário padrão e os sinônimos da transportadora (que prevalecem).
 * {@link #forDocument()} devolve um classificador que carrega os sinônimos de cada transportadora uma única vez.
 */
@Service
public class ComponentClassifier {

    private final ComponentAliasProvider aliases;

    public ComponentClassifier(ComponentAliasProvider aliases) {
        this.aliases = aliases;
    }

    public ComponentCode classify(String carrierCnpj, String componentName) {
        return ComponentDictionary.classify(componentName, aliases.aliasesByCarrierCnpj(carrierCnpj));
    }

    /** Classificador para a normalização de um documento: consulta os sinônimos uma vez por transportadora. */
    public BiFunction<String, String, ComponentCode> forDocument() {
        Map<String, Map<String, String>> cache = new HashMap<>();
        return (carrierCnpj, name) -> ComponentDictionary.classify(name,
                cache.computeIfAbsent(carrierCnpj, aliases::aliasesByCarrierCnpj));
    }
}

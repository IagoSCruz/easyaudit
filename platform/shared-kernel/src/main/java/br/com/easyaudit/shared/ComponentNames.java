package br.com.easyaudit.shared;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normalização de nomes livres de componente de valor ({@code Comp/xNome} do CT-e): sem acentos, em maiúsculas,
 * com qualquer sequência de caracteres não alfanuméricos reduzida a um espaço. "Frete-Peso", "FRETE  PESO" e
 * "frete peso" viram "FRETE PESO".
 */
public final class ComponentNames {

    private ComponentNames() {
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return decomposed.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }
}

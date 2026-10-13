package br.com.easyaudit.shared;

import java.util.Locale;
import java.util.Objects;

/**
 * CNPJ numérico ou alfanumérico (14 caracteres: 12 de base e 2 dígitos verificadores), sempre sem máscara e em maiúsculas.
 * O dígito verificador usa o módulo 11 oficial, com o valor de cada caractere igual ao código ASCII menos 48.
 */
public record Cnpj(String value) {

    private static final int[] WEIGHTS_FIRST = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] WEIGHTS_SECOND = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    public Cnpj {
        Objects.requireNonNull(value, "value");
        if (!isValid(value)) {
            throw new IllegalArgumentException("CNPJ inválido: " + value);
        }
    }

    /** Aceita máscara ({@code 11.222.333/0001-81}) e letras minúsculas. */
    public static Cnpj of(String text) {
        return new Cnpj(normalize(text));
    }

    public static boolean isValid(String text) {
        if (text == null) {
            return false;
        }
        String s = normalize(text);
        if (s.length() != 14 || !s.matches("[0-9A-Z]{12}[0-9]{2}")) {
            return false;
        }
        if (s.chars().distinct().count() == 1) {
            return false;
        }
        int first = checkDigit(s, WEIGHTS_FIRST);
        int second = checkDigit(s, WEIGHTS_SECOND);
        return s.charAt(12) - '0' == first && s.charAt(13) - '0' == second;
    }

    /** Os 8 primeiros caracteres, que identificam a empresa (matriz e filiais). */
    public String root() {
        return value.substring(0, 8);
    }

    private static String normalize(String text) {
        return text.replaceAll("[.\\-/\\s]", "").toUpperCase(Locale.ROOT);
    }

    private static int checkDigit(String s, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (s.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    @Override
    public String toString() {
        return value;
    }
}

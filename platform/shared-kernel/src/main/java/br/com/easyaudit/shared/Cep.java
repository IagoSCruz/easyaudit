package br.com.easyaudit.shared;

import java.util.Objects;

/** CEP com 8 dígitos, sem máscara. */
public record Cep(String value) implements Comparable<Cep> {

    public Cep {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("CEP inválido: " + value);
        }
    }

    /** Aceita {@code 01310-100}. */
    public static Cep of(String text) {
        return new Cep(text.replaceAll("[\\-.\\s]", ""));
    }

    /** O CEP como número, útil para comparar faixas. */
    public int asNumber() {
        return Integer.parseInt(value);
    }

    @Override
    public int compareTo(Cep other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}

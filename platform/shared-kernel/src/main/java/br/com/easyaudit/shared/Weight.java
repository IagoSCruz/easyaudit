package br.com.easyaudit.shared;

import java.math.BigDecimal;
import java.util.Objects;

/** Peso em quilogramas, nunca negativo. */
public record Weight(BigDecimal kg) implements Comparable<Weight> {

    private static final BigDecimal KG_PER_TON = new BigDecimal("1000");

    public static final Weight ZERO = new Weight(BigDecimal.ZERO);

    public Weight {
        Objects.requireNonNull(kg, "kg");
        if (kg.signum() < 0) {
            throw new IllegalArgumentException("Peso não pode ser negativo: " + kg);
        }
    }

    public static Weight ofKg(String text) {
        return new Weight(new BigDecimal(text));
    }

    public static Weight ofKg(BigDecimal kg) {
        return new Weight(kg);
    }

    public static Weight ofTons(BigDecimal tons) {
        return new Weight(tons.multiply(KG_PER_TON));
    }

    public Weight plus(Weight other) {
        return new Weight(kg.add(other.kg));
    }

    /** O maior entre os dois pesos (por exemplo, real versus cubado). */
    public Weight max(Weight other) {
        return compareTo(other) >= 0 ? this : other;
    }

    @Override
    public int compareTo(Weight other) {
        return kg.compareTo(other.kg);
    }

    @Override
    public String toString() {
        return kg.toPlainString();
    }
}

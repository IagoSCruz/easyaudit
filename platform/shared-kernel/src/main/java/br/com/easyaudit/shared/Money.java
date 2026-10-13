package br.com.easyaudit.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Valor monetário em BRL, sempre com 2 casas decimais ({@code HALF_UP}). */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        amount = amount.setScale(2, RoundingMode.HALF_UP);
    }

    public static Money of(String text) {
        return new Money(new BigDecimal(text));
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money abs() {
        return new Money(amount.abs());
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return amount.toPlainString();
    }
}

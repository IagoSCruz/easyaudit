package br.com.easyaudit.shared;

import java.util.Objects;
import java.util.UUID;

/** Identificador de tenant. */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }

    public static TenantId of(String text) {
        return new TenantId(UUID.fromString(text));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}

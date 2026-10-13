package br.com.easyaudit.tenancy.domain;

import java.util.Locale;

import br.com.easyaudit.shared.Cnpj;

/** CNPJ completo (14 caracteres, com dígitos verificadores) ou raiz (8 caracteres) de um tenant. */
public record CnpjEntry(String value, Kind kind) {

    public enum Kind { FULL, ROOT }

    /** @throws InvalidCnpjException quando o texto não é um CNPJ completo válido nem uma raiz de 8 caracteres */
    public static CnpjEntry parse(String input) {
        if (input == null) {
            throw new InvalidCnpjException("CNPJ é obrigatório.");
        }
        String normalized = input.replaceAll("[.\\-/\\s]", "").toUpperCase(Locale.ROOT);
        if (normalized.length() == 8 && normalized.matches("[0-9A-Z]{8}")) {
            return new CnpjEntry(normalized, Kind.ROOT);
        }
        if (normalized.length() == 14 && Cnpj.isValid(normalized)) {
            return new CnpjEntry(normalized, Kind.FULL);
        }
        throw new InvalidCnpjException("CNPJ inválido: " + input);
    }

    /** Os 8 primeiros caracteres, que identificam a empresa. */
    public String root() {
        return value.substring(0, 8);
    }
}

package br.com.easyaudit.shared;

import java.util.Map;
import java.util.Objects;

/** Chave de acesso de documento fiscal eletrônico (44 dígitos, dígito verificador módulo 11). */
public record AccessKey(String value) {

    /** Códigos IBGE das UFs. */
    private static final Map<String, String> UF_BY_CODE = Map.ofEntries(
            Map.entry("11", "RO"), Map.entry("12", "AC"), Map.entry("13", "AM"), Map.entry("14", "RR"),
            Map.entry("15", "PA"), Map.entry("16", "AP"), Map.entry("17", "TO"), Map.entry("21", "MA"),
            Map.entry("22", "PI"), Map.entry("23", "CE"), Map.entry("24", "RN"), Map.entry("25", "PB"),
            Map.entry("26", "PE"), Map.entry("27", "AL"), Map.entry("28", "SE"), Map.entry("29", "BA"),
            Map.entry("31", "MG"), Map.entry("32", "ES"), Map.entry("33", "RJ"), Map.entry("35", "SP"),
            Map.entry("41", "PR"), Map.entry("42", "SC"), Map.entry("43", "RS"), Map.entry("50", "MS"),
            Map.entry("51", "MT"), Map.entry("52", "GO"), Map.entry("53", "DF"));

    public AccessKey {
        Objects.requireNonNull(value, "value");
        if (!isValid(value)) {
            throw new IllegalArgumentException("Chave de acesso inválida: " + value);
        }
    }

    /** Aceita espaços entre os grupos de dígitos. */
    public static AccessKey of(String text) {
        return new AccessKey(text.replaceAll("\\s", ""));
    }

    public static boolean isValid(String text) {
        if (text == null || !text.matches("[0-9]{44}")) {
            return false;
        }
        return text.charAt(43) - '0' == checkDigit(text.substring(0, 43))
                && UF_BY_CODE.containsKey(text.substring(0, 2));
    }

    /** Calcula o dígito verificador dos 43 primeiros dígitos. */
    public static int checkDigit(String first43) {
        int sum = 0;
        int weight = 2;
        for (int i = first43.length() - 1; i >= 0; i--) {
            sum += (first43.charAt(i) - '0') * weight;
            weight = weight == 9 ? 2 : weight + 1;
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    /** Modelo do documento: 55 (NF-e) ou 57 (CT-e), entre outros. */
    public String model() {
        return value.substring(20, 22);
    }

    public boolean isNfe() {
        return "55".equals(model());
    }

    public boolean isCte() {
        return "57".equals(model());
    }

    /** Sigla da UF do emitente. */
    public String uf() {
        return UF_BY_CODE.get(value.substring(0, 2));
    }

    /** Ano e mês de emissão no formato AAMM. */
    public String yearMonth() {
        return value.substring(2, 6);
    }

    /** CNPJ do emitente, nos dígitos 7 a 20 da chave. */
    public Cnpj issuerCnpj() {
        return Cnpj.of(value.substring(6, 20));
    }

    public String series() {
        return value.substring(22, 25);
    }

    public String number() {
        return value.substring(25, 34);
    }

    @Override
    public String toString() {
        return value;
    }
}

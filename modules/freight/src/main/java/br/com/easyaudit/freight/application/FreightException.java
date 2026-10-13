package br.com.easyaudit.freight.application;

/** Erro de regra de negócio do módulo freight, com código estável e status HTTP. */
public class FreightException extends RuntimeException {

    public enum Kind { NOT_FOUND, CONFLICT, INVALID }

    private final Kind kind;
    private final String code;

    public FreightException(Kind kind, String code, String message) {
        super(message);
        this.kind = kind;
        this.code = code;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public static FreightException notFound(String message) {
        return new FreightException(Kind.NOT_FOUND, "NOT_FOUND", message);
    }

    public static FreightException conflict(String code, String message) {
        return new FreightException(Kind.CONFLICT, code, message);
    }

    public static FreightException invalid(String code, String message) {
        return new FreightException(Kind.INVALID, code, message);
    }
}

package br.com.easyaudit.settlement.application;

import java.util.List;

/** Erro de regra de negócio da liquidação, com código estável e o tipo (que vira o status HTTP). */
public class SettlementException extends RuntimeException {

    public enum Kind { NOT_FOUND, CONFLICT, INVALID }

    public record Issue(String field, String message) {
    }

    private final Kind kind;
    private final String code;
    private final List<Issue> issues;

    public SettlementException(Kind kind, String code, String message, List<Issue> issues) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.issues = List.copyOf(issues);
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public List<Issue> issues() {
        return issues;
    }

    public static SettlementException notFound(String message) {
        return new SettlementException(Kind.NOT_FOUND, "NOT_FOUND", message, List.of());
    }

    public static SettlementException conflict(String code, String message) {
        return new SettlementException(Kind.CONFLICT, code, message, List.of());
    }

    public static SettlementException invalid(String code, String message, List<Issue> issues) {
        return new SettlementException(Kind.INVALID, code, message, issues);
    }

    public static SettlementException invalid(String code, String message, String field) {
        return invalid(code, message, List.of(new Issue(field, message)));
    }
}

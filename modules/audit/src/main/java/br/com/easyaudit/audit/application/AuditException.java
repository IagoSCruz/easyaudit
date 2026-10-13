package br.com.easyaudit.audit.application;

import java.util.List;

/** Erro de regra de negócio da auditoria, com código estável e o tipo (que vira o status HTTP). */
public class AuditException extends RuntimeException {

    public enum Kind { NOT_FOUND, CONFLICT, INVALID, BAD_REQUEST }

    public record FieldIssue(String field, String message) {
    }

    private final Kind kind;
    private final String code;
    private final List<FieldIssue> issues;
    private final String currentStatus;

    public AuditException(Kind kind, String code, String message, List<FieldIssue> issues, String currentStatus) {
        super(message);
        this.kind = kind;
        this.code = code;
        this.issues = List.copyOf(issues);
        this.currentStatus = currentStatus;
    }

    public Kind kind() {
        return kind;
    }

    public String code() {
        return code;
    }

    public List<FieldIssue> issues() {
        return issues;
    }

    /** Estado atual do caso, nos conflitos de transição (409). */
    public String currentStatus() {
        return currentStatus;
    }

    public static AuditException notFound(String message) {
        return new AuditException(Kind.NOT_FOUND, "NOT_FOUND", message, List.of(), null);
    }

    public static AuditException conflict(String code, String message) {
        return new AuditException(Kind.CONFLICT, code, message, List.of(), null);
    }

    public static AuditException conflict(String code, String message, String currentStatus) {
        return new AuditException(Kind.CONFLICT, code, message, List.of(), currentStatus);
    }

    public static AuditException invalid(String code, String message, List<FieldIssue> issues) {
        return new AuditException(Kind.INVALID, code, message, issues, null);
    }

    public static AuditException badRequest(String code, String message) {
        return new AuditException(Kind.BAD_REQUEST, code, message, List.of(), null);
    }

    public static AuditException invalid(String code, String message) {
        return invalid(code, message, List.of());
    }
}

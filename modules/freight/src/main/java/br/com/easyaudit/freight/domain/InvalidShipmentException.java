package br.com.easyaudit.freight.domain;

import java.util.List;

/** Entradas do cálculo inválidas; cada erro indica o campo. */
public class InvalidShipmentException extends RuntimeException {

    /** Campo inválido e o motivo. */
    public record FieldIssue(String field, String message) {
    }

    private final List<FieldIssue> issues;

    public InvalidShipmentException(List<FieldIssue> issues) {
        super("Entradas do cálculo inválidas: " + issues);
        this.issues = List.copyOf(issues);
    }

    public List<FieldIssue> issues() {
        return issues;
    }
}

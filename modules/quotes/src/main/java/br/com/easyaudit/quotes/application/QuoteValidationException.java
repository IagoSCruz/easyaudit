package br.com.easyaudit.quotes.application;

import java.util.List;

/** A cotação tem campos inválidos; todos são devolvidos juntos (422), cada um com o nome do campo. */
public class QuoteValidationException extends RuntimeException {

    public record FieldIssue(String field, String message) {
    }

    private final List<FieldIssue> issues;

    public QuoteValidationException(List<FieldIssue> issues) {
        super("A cotação tem " + issues.size() + " campo(s) inválido(s).");
        this.issues = List.copyOf(issues);
    }

    public List<FieldIssue> issues() {
        return issues;
    }
}

package br.com.easyaudit.freight.application;

import java.util.List;

/** A tabela tem erros de validação; todos são devolvidos juntos (422), cada um com a localização. */
public class TableValidationException extends RuntimeException {

    /** Erro com a localização já traduzida (caminho JSON ou aba/linha/coluna). */
    public record Located(String location, String message) {
    }

    private final List<Located> errors;

    public TableValidationException(List<Located> errors) {
        super("A tabela de frete tem " + errors.size() + " erro(s) de validação.");
        this.errors = List.copyOf(errors);
    }

    public List<Located> errors() {
        return errors;
    }
}

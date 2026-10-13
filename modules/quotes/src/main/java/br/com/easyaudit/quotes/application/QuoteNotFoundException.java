package br.com.easyaudit.quotes.application;

public class QuoteNotFoundException extends RuntimeException {

    public QuoteNotFoundException() {
        super("Cotação não encontrada.");
    }
}

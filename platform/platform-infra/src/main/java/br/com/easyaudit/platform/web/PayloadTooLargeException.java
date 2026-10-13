package br.com.easyaudit.platform.web;

/** Corpo ou coleção acima do limite permitido; vira 413 com o código {@code PAYLOAD_TOO_LARGE}. */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException(String message) {
        super(message);
    }
}

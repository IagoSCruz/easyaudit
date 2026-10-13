package br.com.easyaudit.documents.infrastructure.xml;

/** Assinatura digital ausente, adulterada ou que não cobre o elemento esperado. */
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException(String message) {
        super(message);
    }

    public InvalidSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}

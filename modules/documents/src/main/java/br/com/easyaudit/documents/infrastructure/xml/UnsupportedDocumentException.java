package br.com.easyaudit.documents.infrastructure.xml;

/** XML bem formado, mas de um tipo ou versão que o sistema não aceita. */
public class UnsupportedDocumentException extends RuntimeException {

    public UnsupportedDocumentException(String message) {
        super(message);
    }
}

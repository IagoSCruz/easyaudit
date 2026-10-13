package br.com.easyaudit.documents.application;

public class DocumentNotFoundException extends RuntimeException {

    public DocumentNotFoundException() {
        super("Documento não encontrado.");
    }
}

package br.com.easyaudit.documents.infrastructure.xml;

/** XML corrompido, com DTD/entidades (XXE) ou sem a estrutura mínima. */
public class MalformedXmlException extends RuntimeException {

    public MalformedXmlException(String message) {
        super(message);
    }

    public MalformedXmlException(String message, Throwable cause) {
        super(message, cause);
    }
}

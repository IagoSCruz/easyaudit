package br.com.easyaudit.documents.infrastructure.xml;

/** O conteúdo do documento não permite montar o modelo canônico (campo obrigatório ausente ou inválido). */
public class NormalizationException extends RuntimeException {

    public NormalizationException(String message) {
        super(message);
    }
}

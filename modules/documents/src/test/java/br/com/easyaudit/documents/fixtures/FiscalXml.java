package br.com.easyaudit.documents.fixtures;

import java.nio.charset.StandardCharsets;

/** XML fiscal de teste: os bytes e a chave de acesso do documento (ou do documento cancelado, nos eventos). */
public record FiscalXml(byte[] bytes, String accessKey) {

    public String text() {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Cópia com o texto trocado, sem refazer a assinatura (para simular adulteração). */
    public FiscalXml replacing(String from, String to) {
        String text = text();
        if (!text.contains(from)) {
            throw new IllegalArgumentException("Trecho não encontrado: " + from);
        }
        return new FiscalXml(text.replace(from, to).getBytes(StandardCharsets.UTF_8), accessKey);
    }
}

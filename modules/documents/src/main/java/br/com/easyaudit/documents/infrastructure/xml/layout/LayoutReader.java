package br.com.easyaudit.documents.infrastructure.xml.layout;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Lê CT-e e NF-e para os DTOs de layout. Tolerante a elementos desconhecidos
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES=false}); a leitura segura de DTD/entidades é garantida antes, na ingestão
 * ({@code FiscalHeaderParser}), e o mapper do Jackson não resolve entidades externas.
 */
public final class LayoutReader {

    private static final XmlMapper MAPPER = XmlMapper.builder()
            .defaultUseWrapper(false)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private LayoutReader() {
    }

    /** @throws LayoutException XML que não corresponde ao layout esperado */
    public static CteLayout readCte(byte[] xml) {
        try {
            return MAPPER.readValue(xml, CteLayout.class);
        } catch (JacksonException e) {
            throw new LayoutException("CT-e fora do layout esperado: " + e.getOriginalMessage(), e);
        }
    }

    public static NfeLayout readNfe(byte[] xml) {
        try {
            return MAPPER.readValue(xml, NfeLayout.class);
        } catch (JacksonException e) {
            throw new LayoutException("NF-e fora do layout esperado: " + e.getOriginalMessage(), e);
        }
    }

    /** O XML não segue o layout esperado e não pode ser normalizado. */
    public static class LayoutException extends RuntimeException {
        public LayoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

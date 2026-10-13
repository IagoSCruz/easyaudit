package br.com.easyaudit.documents.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades {@code easyaudit.documents.*}.
 *
 * @param acceptHomologation aceita documentos de homologação ({@code tpAmb} = 2); falso em produção
 */
@ConfigurationProperties("easyaudit.documents")
public record DocumentsProperties(boolean acceptHomologation) {
}

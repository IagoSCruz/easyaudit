package br.com.easyaudit.documents;

import br.com.easyaudit.documents.application.DocumentsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DocumentsProperties.class)
class DocumentsConfiguration {
}

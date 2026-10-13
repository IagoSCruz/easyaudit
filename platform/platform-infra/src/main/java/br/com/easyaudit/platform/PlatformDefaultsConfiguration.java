package br.com.easyaudit.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

/** Carrega os padrões da plataforma (precedência baixa). Importada pelas configurações da plataforma. */
@Configuration(proxyBeanMethods = false)
@PropertySource("classpath:easyaudit-platform-defaults.properties")
public class PlatformDefaultsConfiguration {
}

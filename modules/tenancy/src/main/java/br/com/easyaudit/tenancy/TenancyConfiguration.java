package br.com.easyaudit.tenancy;

import br.com.easyaudit.tenancy.application.CredentialsProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CredentialsProperties.class)
class TenancyConfiguration {
}

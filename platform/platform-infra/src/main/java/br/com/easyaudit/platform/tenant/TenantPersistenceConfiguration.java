package br.com.easyaudit.platform.tenant;

import br.com.easyaudit.platform.PlatformDefaultsConfiguration;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.cfg.MultiTenancySettings;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Import(PlatformDefaultsConfiguration.class)
public class TenantPersistenceConfiguration {

    @Bean
    HibernatePropertiesCustomizer tenantIdentifierResolverCustomizer(TenantIdentifierResolver resolver) {
        return properties -> properties.put(MultiTenancySettings.MULTI_TENANT_IDENTIFIER_RESOLVER, resolver);
    }

    @Bean
    TenantIdentifierResolver tenantIdentifierResolver() {
        return new TenantIdentifierResolver();
    }

    @Bean
    PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new TenantAwareJpaTransactionManager(emf);
    }
}

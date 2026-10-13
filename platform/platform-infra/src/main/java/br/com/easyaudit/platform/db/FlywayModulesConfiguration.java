package br.com.easyaudit.platform.db;

import br.com.easyaudit.platform.PlatformDefaultsConfiguration;
import java.util.List;

import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Uma única configuração do Flyway com uma {@code location} por módulo ({@code classpath:db/migration/<modulo>}).
 * As versões são timestamps ({@code V202610130900__audit_create_case.sql}), então módulos nunca colidam.
 * O Flyway roda com a role dona ({@code easyaudit_owner}), configurada em {@code spring.flyway.user}.
 */
@Configuration(proxyBeanMethods = false)
@Import(PlatformDefaultsConfiguration.class)
public class FlywayModulesConfiguration {

    public static final List<String> MODULES =
            List.of("platform", "tenancy", "documents", "freight", "quotes", "audit", "settlement");

    @Bean
    FlywayConfigurationCustomizer moduleMigrationLocations() {
        return configuration -> configuration.locations(
                MODULES.stream().map(m -> "classpath:db/migration/" + m).toArray(String[]::new));
    }
}

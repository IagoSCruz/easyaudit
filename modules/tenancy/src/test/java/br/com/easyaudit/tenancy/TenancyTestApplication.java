package br.com.easyaudit.tenancy;

import br.com.easyaudit.platform.db.FlywayModulesConfiguration;
import br.com.easyaudit.platform.security.SecurityConfiguration;
import br.com.easyaudit.platform.tenant.TenantPersistenceConfiguration;
import br.com.easyaudit.platform.testsupport.TestTokens;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/** Aplicação mínima para testar o módulo: só o tenancy e a infraestrutura da plataforma. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan("br.com.easyaudit.tenancy")
@Import({FlywayModulesConfiguration.class, TenantPersistenceConfiguration.class, SecurityConfiguration.class,
        TestTokens.DecoderConfiguration.class})
class TenancyTestApplication {
}

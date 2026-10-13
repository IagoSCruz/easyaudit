package br.com.easyaudit.quotes;

import br.com.easyaudit.platform.db.FlywayModulesConfiguration;
import br.com.easyaudit.platform.messaging.MessagingConfiguration;
import br.com.easyaudit.platform.security.SecurityConfiguration;
import br.com.easyaudit.platform.tenant.TenantPersistenceConfiguration;
import br.com.easyaudit.platform.testsupport.TestTokens;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/** Aplicação mínima para testar o módulo: quotes, documents (consulta de NF-es), tenancy (webhooks) e a plataforma. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan({"br.com.easyaudit.quotes", "br.com.easyaudit.documents", "br.com.easyaudit.tenancy",
        "br.com.easyaudit.freight"})
@Import({MessagingConfiguration.class, FlywayModulesConfiguration.class, TenantPersistenceConfiguration.class,
        SecurityConfiguration.class, TestTokens.DecoderConfiguration.class})
class QuotesTestApplication {
}

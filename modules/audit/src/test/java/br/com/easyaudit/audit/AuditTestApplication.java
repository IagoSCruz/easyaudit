package br.com.easyaudit.audit;

import br.com.easyaudit.platform.db.FlywayModulesConfiguration;
import br.com.easyaudit.platform.messaging.MessagingConfiguration;
import br.com.easyaudit.platform.security.SecurityConfiguration;
import br.com.easyaudit.platform.tenant.TenantPersistenceConfiguration;
import br.com.easyaudit.platform.testsupport.TestTokens;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/** Aplicação de teste do módulo audit: junta os módulos de que a auditoria depende. */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan({"br.com.easyaudit.audit", "br.com.easyaudit.documents", "br.com.easyaudit.tenancy",
        "br.com.easyaudit.freight", "br.com.easyaudit.quotes"})
@Import({MessagingConfiguration.class, FlywayModulesConfiguration.class, TenantPersistenceConfiguration.class,
        SecurityConfiguration.class, TestTokens.DecoderConfiguration.class})
class AuditTestApplication {
}

package br.com.easyaudit.settlement;

import br.com.easyaudit.platform.db.FlywayModulesConfiguration;
import br.com.easyaudit.platform.messaging.MessagingConfiguration;
import br.com.easyaudit.platform.security.SecurityConfiguration;
import br.com.easyaudit.platform.tenant.TenantPersistenceConfiguration;
import br.com.easyaudit.platform.testsupport.TestTokens;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

/** Aplicação de teste do módulo settlement: a liquidação mais o audit (e os módulos de que ele depende). */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan({"br.com.easyaudit.settlement", "br.com.easyaudit.audit", "br.com.easyaudit.documents",
        "br.com.easyaudit.tenancy", "br.com.easyaudit.freight", "br.com.easyaudit.quotes"})
@Import({MessagingConfiguration.class, FlywayModulesConfiguration.class, TenantPersistenceConfiguration.class,
        SecurityConfiguration.class, TestTokens.DecoderConfiguration.class})
class SettlementTestApplication {
}

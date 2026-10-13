package br.com.easyaudit.platform.tenant;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(TenantPersistenceConfiguration.class)
class TenantTestApplication {
}

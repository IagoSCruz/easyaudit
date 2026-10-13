package br.com.easyaudit.platform.db;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import(FlywayModulesConfiguration.class)
class FlywayTestApplication {
}

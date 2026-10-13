package br.com.easyaudit.platform.security;

import br.com.easyaudit.platform.testsupport.TestTokens;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({SecurityConfiguration.class, TestTokens.DecoderConfiguration.class})
class SecurityTestApplication {
}

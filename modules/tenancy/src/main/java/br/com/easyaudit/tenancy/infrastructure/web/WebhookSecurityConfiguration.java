package br.com.easyaudit.tenancy.infrastructure.web;

import java.time.Clock;

import br.com.easyaudit.tenancy.api.TenantDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Cadeia de segurança de {@code /webhooks/**}: autenticação por HMAC, sem sessão. */
@Configuration(proxyBeanMethods = false)
class WebhookSecurityConfiguration {

    static final long DEFAULT_MAX_BODY_BYTES = 20L * 1024 * 1024;

    @Bean
    @Order(2)
    SecurityFilterChain webhookSecurity(HttpSecurity http, TenantDirectory directory,
            @Value("${easyaudit.webhooks.max-body-bytes:" + DEFAULT_MAX_BODY_BYTES + "}") long maxBodyBytes)
            throws Exception {
        http.securityMatcher("/webhooks/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(new HmacAuthenticationFilter(directory, maxBodyBytes, Clock.systemUTC()),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

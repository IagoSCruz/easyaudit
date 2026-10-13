package br.com.easyaudit.platform.security;

import br.com.easyaudit.platform.PlatformDefaultsConfiguration;
import br.com.easyaudit.platform.web.GlobalProblemHandler;
import br.com.easyaudit.platform.web.Problems;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Segurança base. {@code /api/**} e {@code /admin/**}: JWT do provedor OIDC; {@code /api/**} exige a claim
 * {@code tenant_id}; {@code /admin/**} exige {@code PLATFORM_ADMIN}. O health é público e o restante é negado.
 * Os webhooks (HMAC) entram com a própria cadeia, antes da padrão.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@Import({PlatformDefaultsConfiguration.class, GlobalProblemHandler.class})
public class SecurityConfiguration {

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
            // mantém o WWW-Authenticate padrão do Bearer e troca o corpo por um problema RFC 9457
            new BearerTokenAuthenticationEntryPoint().commence(request, response, ex);
            Problems.write(response, Problems.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Token ausente, expirado ou inválido."));
        };
        AccessDeniedHandler deniedHandler = (request, response, ex) -> Problems.write(response,
                Problems.of(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acesso negado."));

        http.securityMatcher("/api/**", "/admin/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/**").hasRole("PLATFORM_ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(RealmRolesConverter.jwtAuthenticationConverter()))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .addFilterAfter(new TenantClaimFilter(), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain defaultSecurity(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);
        return http.build();
    }
}

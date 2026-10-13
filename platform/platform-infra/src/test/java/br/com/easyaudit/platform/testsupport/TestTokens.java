package br.com.easyaudit.platform.testsupport;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Tokens JWT HS256 assinados com um segredo de teste, e o {@link JwtDecoder} que os valida. */
public final class TestTokens {

    public static final byte[] SECRET = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private TestTokens() {
    }

    /** Cria um header {@code Authorization} com o token. */
    public static String bearer(String tenantId, String... roles) {
        return "Bearer " + token(tenantId, Instant.now().plusSeconds(600), SECRET, roles);
    }

    public static String token(String tenantId, Instant expiresAt, byte[] secret, String... roles) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject("user-1")
                    .issueTime(Date.from(expiresAt.minusSeconds(3600)))
                    .expirationTime(Date.from(expiresAt))
                    .claim("realm_access", Map.of("roles", List.of(roles)));
            if (tenantId != null) {
                builder.claim("tenant_id", tenantId);
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), builder.build());
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Importe esta configuração em testes de API para validar os tokens de {@link #bearer}. */
    @Configuration(proxyBeanMethods = false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "easyaudit.test.real-keycloak", havingValue = "false", matchIfMissing = true)
    public static class DecoderConfiguration {

        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(SECRET, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256).build();
        }
    }
}

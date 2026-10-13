package br.com.easyaudit.platform.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Converte os papéis do realm ({@code realm_access.roles}) em autoridades {@code ROLE_*}. */
public final class RealmRolesConverter {

    private RealmRolesConverter() {
    }

    public static Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(RealmRolesConverter::authorities);
        return converter;
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Object realmAccess = jwt.getClaim("realm_access");
        if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> roles) {
            return ((List<Object>) roles).stream()
                    .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                    .toList();
        }
        return List.of();
    }
}

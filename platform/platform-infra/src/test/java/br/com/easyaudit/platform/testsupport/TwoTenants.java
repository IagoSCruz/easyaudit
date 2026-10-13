package br.com.easyaudit.platform.testsupport;

import br.com.easyaudit.shared.TenantId;

/**
 * Fixture de dois tenants para testes de isolamento. Os ids são os mesmos dos usuários de teste do realm do
 * Keycloak local ({@code deploy/local/keycloak}), então tokens de teste e tokens reais combinam.
 */
public final class TwoTenants {

    public static final TenantId A = TenantId.of("11111111-1111-4111-8111-111111111111");
    public static final TenantId B = TenantId.of("22222222-2222-4222-8222-222222222222");

    private TwoTenants() {
    }

    /** Header {@code Authorization} de um usuário do tenant A com os papéis informados. */
    public static String bearerA(String... roles) {
        return TestTokens.bearer(A.toString(), roles);
    }

    /** Header {@code Authorization} de um usuário do tenant B com os papéis informados. */
    public static String bearerB(String... roles) {
        return TestTokens.bearer(B.toString(), roles);
    }
}

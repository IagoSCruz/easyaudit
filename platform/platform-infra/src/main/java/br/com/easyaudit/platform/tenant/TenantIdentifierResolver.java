package br.com.easyaudit.platform.tenant;

import java.util.UUID;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * Entrega ao Hibernate o tenant do {@link TenantContext}, usado por {@code @TenantId} para filtrar e preencher
 * a coluna. Fora de um escopo de tenant (jobs de plataforma), devolve o identificador "nenhum tenant", que não
 * casa com nenhum dado de tenant.
 */
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID> {

    public static final UUID NO_TENANT = new UUID(0L, 0L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantContext.current().map(t -> t.value()).orElse(NO_TENANT);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }
}

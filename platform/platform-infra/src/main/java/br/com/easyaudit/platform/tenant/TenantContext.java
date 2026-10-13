package br.com.easyaudit.platform.tenant;

import java.util.Optional;
import java.util.concurrent.Callable;

import br.com.easyaudit.shared.TenantId;

/**
 * Tenant da execução corrente, guardado em um {@link ScopedValue}: o valor vale só dentro do escopo
 * ({@code runAs}/{@code callAs}), inclusive para threads virtuais criadas por structured concurrency.
 */
public final class TenantContext {

    /** Chave do MDC usada nos logs estruturados. */
    public static final String MDC_KEY = "tenantId";

    private static final ScopedValue<TenantId> CURRENT = ScopedValue.newInstance();

    private TenantContext() {
    }

    public static Optional<TenantId> current() {
        return CURRENT.isBound() ? Optional.of(CURRENT.get()) : Optional.empty();
    }

    /** O tenant corrente; falha se a execução não estiver dentro de um escopo de tenant. */
    public static TenantId require() {
        return current().orElseThrow(() -> new IllegalStateException("Nenhum tenant no contexto"));
    }

    /** Para chamadas que lançam {@code Throwable}: {@code TenantContext.where(t).call(op)}. */
    public static ScopedValue.Carrier where(TenantId tenant) {
        return ScopedValue.where(CURRENT, tenant);
    }

    public static void runAs(TenantId tenant, Runnable action) {
        ScopedValue.where(CURRENT, tenant).run(action);
    }

    public static <T> T callAs(TenantId tenant, Callable<T> action) throws Exception {
        return ScopedValue.where(CURRENT, tenant).call(action::call);
    }
}

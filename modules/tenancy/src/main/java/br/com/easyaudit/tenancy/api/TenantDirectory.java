package br.com.easyaudit.tenancy.api;

import java.util.Optional;

import br.com.easyaudit.shared.Cnpj;
import br.com.easyaudit.shared.TenantId;

/** Fachada do módulo tenancy para os demais módulos (posse de CNPJ e resolução de credenciais de webhook). */
public interface TenantDirectory {

    /**
     * Indica se o CNPJ pertence ao tenant: por entrada exata (CNPJ completo) ou pela raiz cadastrada, que cobre
     * todas as filiais.
     */
    boolean ownsCnpj(TenantId tenant, Cnpj cnpj);

    /** Credencial pelo {@code keyId} público (ativa ou revogada), ou vazio se o {@code keyId} não existe. */
    Optional<WebhookCredential> resolveCredential(String keyId);

    /**
     * Credencial de integração com o segredo em claro, apenas para verificar o HMAC em memória.
     * O {@code toString} não expõe o segredo.
     */
    record WebhookCredential(TenantId tenant, String keyId, String secret, boolean active) {

        @Override
        public String toString() {
            return "WebhookCredential[tenant=" + tenant + ", keyId=" + keyId + ", active=" + active + "]";
        }
    }
}

package br.com.easyaudit.tenancy.application;

import java.util.Optional;

import br.com.easyaudit.shared.Cnpj;
import br.com.easyaudit.shared.TenantId;
import br.com.easyaudit.tenancy.api.TenantDirectory;
import br.com.easyaudit.tenancy.domain.CnpjOwnership;
import br.com.easyaudit.tenancy.domain.IntegrationCredential;
import br.com.easyaudit.tenancy.infrastructure.persistence.CredentialRepository;
import br.com.easyaudit.tenancy.infrastructure.persistence.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultTenantDirectory implements TenantDirectory {

    private final TenantRepository tenants;
    private final CredentialRepository credentials;
    private final CredentialCipher cipher;

    DefaultTenantDirectory(TenantRepository tenants, CredentialRepository credentials, CredentialCipher cipher) {
        this.tenants = tenants;
        this.credentials = credentials;
        this.cipher = cipher;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean ownsCnpj(TenantId tenant, Cnpj cnpj) {
        return CnpjOwnership.owns(tenants.cnpjs(tenant.value()), cnpj);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<WebhookCredential> resolveCredential(String keyId) {
        return credentials.findByKeyId(keyId).map(stored -> {
            var c = stored.credential();
            String secret = cipher.decrypt(c.keyId(), stored.keyVersion(), stored.secretCiphertext());
            return new WebhookCredential(new TenantId(c.tenantId()), c.keyId(), secret,
                    c.status() == IntegrationCredential.Status.ACTIVE);
        });
    }
}

package br.com.easyaudit.tenancy.application;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.tenancy.domain.IntegrationCredential;
import br.com.easyaudit.tenancy.infrastructure.persistence.CredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CredentialService {

    /** Resultado da criação: o segredo em claro existe apenas aqui, e é exibido uma única vez. */
    public record Created(IntegrationCredential credential, String secret) {
    }

    private final CredentialRepository repository;
    private final CredentialCipher cipher;
    private final SecureRandom random = new SecureRandom();

    public CredentialService(CredentialRepository repository, CredentialCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    @Transactional
    public Created create(UUID tenantId, String description) {
        String keyId = "ea_" + randomToken(12);
        String secret = randomToken(32);
        IntegrationCredential credential = repository.insert(tenantId, keyId, description,
                cipher.encrypt(keyId, secret), cipher.activeVersion());
        return new Created(credential, secret);
    }

    @Transactional(readOnly = true)
    public List<IntegrationCredential> list(UUID tenantId) {
        return repository.findByTenant(tenantId);
    }

    /** @throws TenantNotFoundException a credencial não existe neste tenant (a de outro tenant é tratada como inexistente) */
    @Transactional
    public IntegrationCredential revoke(UUID tenantId, String keyId) {
        if (!repository.revoke(tenantId, keyId)) {
            throw new TenantNotFoundException("Credencial não encontrada.");
        }
        return repository.findByKeyId(keyId).orElseThrow().credential();
    }

    private String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}

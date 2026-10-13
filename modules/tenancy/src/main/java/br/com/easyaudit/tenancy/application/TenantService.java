package br.com.easyaudit.tenancy.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.tenancy.domain.CnpjEntry;
import br.com.easyaudit.tenancy.domain.Tenant;
import br.com.easyaudit.tenancy.infrastructure.persistence.TenantRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantService {

    private final TenantRepository repository;

    public TenantService(TenantRepository repository) {
        this.repository = repository;
    }

    /**
     * Cria o tenant com os CNPJs informados (completos ou raízes).
     *
     * @throws br.com.easyaudit.tenancy.domain.InvalidCnpjException CNPJ com dígito verificador inválido
     * @throws TenancyConflictException slug duplicado ou CNPJ/raiz de outro tenant (nada é gravado)
     */
    @Transactional
    public Tenant create(String name, String slug, List<String> cnpjs) {
        // valida tudo antes de gravar; deduplica entradas iguais
        var entries = new LinkedHashMap<String, CnpjEntry>();
        cnpjs.forEach(input -> {
            CnpjEntry entry = CnpjEntry.parse(input);
            entries.putIfAbsent(entry.value(), entry);
        });
        UUID id;
        try {
            id = repository.insertTenant(name, slug);
        } catch (DuplicateKeyException e) {
            throw new TenancyConflictException("TENANT_SLUG_TAKEN", "Já existe um tenant com o slug '" + slug + "'.");
        }
        entries.values().forEach(entry -> registerCnpj(id, entry));
        return repository.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Tenant get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new TenantNotFoundException("Tenant não encontrado."));
    }

    @Transactional(readOnly = true)
    public List<Tenant> list() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public List<CnpjEntry> listCnpjs(UUID tenantId) {
        return repository.cnpjs(tenantId);
    }

    /**
     * Inclui um CNPJ completo ou raiz no tenant. Repetir uma entrada já existente é idempotente.
     *
     * @throws br.com.easyaudit.tenancy.domain.InvalidCnpjException dígito verificador inválido
     * @throws TenancyConflictException o CNPJ ou a raiz pertence a outro tenant
     */
    @Transactional
    public CnpjEntry addCnpj(UUID tenantId, String input) {
        CnpjEntry entry = CnpjEntry.parse(input);
        registerCnpj(tenantId, entry);
        return entry;
    }

    /** @throws TenantNotFoundException a entrada não existe neste tenant */
    @Transactional
    public void removeCnpj(UUID tenantId, String input) {
        CnpjEntry entry = CnpjEntry.parse(input);
        if (!repository.deleteCnpj(tenantId, entry.value())) {
            throw new TenantNotFoundException("CNPJ não encontrado neste tenant.");
        }
    }

    /** Registra a entrada para o tenant, recusando raízes que já pertencem a outro. */
    void registerCnpj(UUID tenantId, CnpjEntry entry) {
        UUID owner = repository.claimRoot(entry.root(), tenantId);
        if (!owner.equals(tenantId)) {
            throw new TenancyConflictException("CNPJ_OWNED_BY_OTHER_TENANT",
                    "O CNPJ " + entry.value() + " pertence a outro tenant.");
        }
        repository.insertCnpj(tenantId, entry);
    }
}

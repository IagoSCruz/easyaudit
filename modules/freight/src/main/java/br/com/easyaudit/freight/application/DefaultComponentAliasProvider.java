package br.com.easyaudit.freight.application;

import java.util.Map;

import br.com.easyaudit.freight.api.ComponentAliasProvider;
import br.com.easyaudit.freight.infrastructure.persistence.ComponentAliasRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultComponentAliasProvider implements ComponentAliasProvider {

    private final ComponentAliasRepository aliases;

    DefaultComponentAliasProvider(ComponentAliasRepository aliases) {
        this.aliases = aliases;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, String> aliasesByCarrierCnpj(String carrierCnpj) {
        return aliases.normalizedByCarrierCnpj(carrierCnpj);
    }
}

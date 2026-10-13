package br.com.easyaudit.freight.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import br.com.easyaudit.freight.domain.ComponentAlias;
import br.com.easyaudit.freight.infrastructure.persistence.CarrierRepository;
import br.com.easyaudit.freight.infrastructure.persistence.ComponentAliasRepository;
import br.com.easyaudit.shared.ComponentNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComponentAliasService {

    public static final Set<String> COMPONENT_CODES = Set.of("FREIGHT_WEIGHT", "AD_VALOREM", "GRIS", "TOLL", "TDA",
            "TDE", "DISPATCH_FEE", "ICMS", "OTHER");

    private final CarrierRepository carriers;
    private final ComponentAliasRepository aliases;

    public ComponentAliasService(CarrierRepository carriers, ComponentAliasRepository aliases) {
        this.carriers = carriers;
        this.aliases = aliases;
    }

    @Transactional(readOnly = true)
    public List<ComponentAlias> list(UUID carrierId) {
        requireCarrier(carrierId);
        return aliases.findByCarrier(carrierId);
    }

    /**
     * @throws FreightException transportadora inexistente (404), código inválido (422) ou nome já mapeado (409)
     */
    @Transactional
    public ComponentAlias create(UUID carrierId, String alias, String componentCode) {
        requireCarrier(carrierId);
        if (!COMPONENT_CODES.contains(componentCode)) {
            throw FreightException.invalid("INVALID_COMPONENT_CODE", "Código de componente inválido: " + componentCode);
        }
        String normalized = ComponentNames.normalize(alias);
        if (normalized.isEmpty()) {
            throw FreightException.invalid("INVALID_ALIAS", "O sinônimo precisa ter letras ou números.");
        }
        UUID id = aliases.insert(carrierId, alias.trim(), normalized, componentCode).orElseThrow(
                () -> FreightException.conflict("ALIAS_ALREADY_MAPPED",
                        "O nome '" + alias.trim() + "' já está mapeado para esta transportadora."));
        return aliases.findById(carrierId, id).orElseThrow();
    }

    @Transactional
    public void delete(UUID carrierId, UUID aliasId) {
        requireCarrier(carrierId);
        if (!aliases.delete(carrierId, aliasId)) {
            throw FreightException.notFound("Sinônimo não encontrado.");
        }
    }

    private void requireCarrier(UUID carrierId) {
        carriers.findById(carrierId).orElseThrow(() -> FreightException.notFound("Transportadora não encontrada."));
    }
}

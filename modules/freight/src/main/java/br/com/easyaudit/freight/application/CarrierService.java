package br.com.easyaudit.freight.application;

import java.util.List;
import java.util.UUID;

import br.com.easyaudit.freight.domain.Carrier;
import br.com.easyaudit.freight.infrastructure.persistence.CarrierRepository;
import br.com.easyaudit.shared.Cnpj;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CarrierService {

    private final CarrierRepository carriers;

    public CarrierService(CarrierRepository carriers) {
        this.carriers = carriers;
    }

    /**
     * Cadastro manual. @throws FreightException CNPJ inválido (422) ou já existente no tenant (409)
     */
    @Transactional
    public Carrier create(String cnpjInput, String name) {
        String cnpj = requireValidCnpj(cnpjInput);
        UUID id = carriers.insert(cnpj, name.trim(), false).orElseThrow(() -> FreightException.conflict(
                "CARRIER_CNPJ_TAKEN", "Já existe uma transportadora com o CNPJ " + cnpj + " neste tenant."));
        return carriers.findById(id).orElseThrow();
    }

    /**
     * Cadastro automático a partir de um CT-e: cria se o CNPJ ainda não existe; se existe, não altera nada.
     *
     * @return {@code true} se a transportadora foi criada agora
     */
    @Transactional
    public boolean registerIfAbsent(String cnpj, String name) {
        return carriers.insert(cnpj, name.trim(), true).isPresent();
    }

    @Transactional(readOnly = true)
    public List<Carrier> list(Boolean active) {
        return carriers.findAll(active);
    }

    @Transactional(readOnly = true)
    public Carrier get(UUID id) {
        return carriers.findById(id).orElseThrow(() -> FreightException.notFound("Transportadora não encontrada."));
    }

    @Transactional
    public Carrier update(UUID id, String name, Boolean active) {
        Carrier current = get(id);
        carriers.update(id, name != null ? name.trim() : current.name(), active != null ? active : current.active());
        return get(id);
    }

    private static String requireValidCnpj(String input) {
        try {
            return Cnpj.of(input).value();
        } catch (IllegalArgumentException e) {
            throw FreightException.invalid("INVALID_CNPJ", "CNPJ inválido: " + input);
        }
    }
}

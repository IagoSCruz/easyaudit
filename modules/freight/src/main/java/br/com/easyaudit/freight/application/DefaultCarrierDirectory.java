package br.com.easyaudit.freight.application;

import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.freight.api.CarrierDirectory;
import br.com.easyaudit.freight.domain.Carrier;
import br.com.easyaudit.freight.infrastructure.persistence.CarrierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class DefaultCarrierDirectory implements CarrierDirectory {

    private final CarrierRepository carriers;

    DefaultCarrierDirectory(CarrierRepository carriers) {
        this.carriers = carriers;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CarrierRef> findById(UUID carrierId) {
        return carriers.findById(carrierId).map(DefaultCarrierDirectory::ref);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CarrierRef> findByCnpj(String cnpj) {
        return carriers.findByCnpj(cnpj).map(DefaultCarrierDirectory::ref);
    }

    private static CarrierRef ref(Carrier c) {
        return new CarrierRef(c.id(), c.cnpj(), c.name(), c.active());
    }
}

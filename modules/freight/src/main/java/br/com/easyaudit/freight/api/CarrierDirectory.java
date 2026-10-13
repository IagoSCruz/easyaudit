package br.com.easyaudit.freight.api;

import java.util.Optional;
import java.util.UUID;

/** Consulta de transportadoras do tenant corrente, para outros módulos. */
public interface CarrierDirectory {

    record CarrierRef(UUID id, String cnpj, String name, boolean active) {
    }

    Optional<CarrierRef> findById(UUID carrierId);

    /** Transportadora do tenant pelo CNPJ completo (14 caracteres, sem máscara). */
    Optional<CarrierRef> findByCnpj(String cnpj);
}

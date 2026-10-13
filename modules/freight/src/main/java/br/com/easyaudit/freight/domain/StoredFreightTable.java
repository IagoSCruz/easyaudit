package br.com.easyaudit.freight.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Versão de tabela de frete como gravada: metadados e o conteúdo canônico em JSON. */
public record StoredFreightTable(UUID id, UUID carrierId, String name, int version, Status status,
        LocalDate validFrom, LocalDate validTo, boolean includesIcms, OffsetDateTime createdAt,
        OffsetDateTime activatedAt, String contentJson) {

    public enum Status { DRAFT, ACTIVE, ARCHIVED }
}

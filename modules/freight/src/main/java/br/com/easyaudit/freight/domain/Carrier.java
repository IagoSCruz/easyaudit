package br.com.easyaudit.freight.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Carrier(UUID id, String cnpj, String name, boolean active, boolean autoRegistered,
        OffsetDateTime createdAt) {
}

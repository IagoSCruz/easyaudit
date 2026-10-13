package br.com.easyaudit.tenancy.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Credencial de integração: {@code keyId} público; o segredo nunca faz parte desta visão. */
public record IntegrationCredential(UUID id, UUID tenantId, String keyId, String description, Status status,
        OffsetDateTime createdAt) {

    public enum Status { ACTIVE, REVOKED }
}

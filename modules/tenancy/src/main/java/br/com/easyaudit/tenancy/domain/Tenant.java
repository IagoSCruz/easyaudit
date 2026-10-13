package br.com.easyaudit.tenancy.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record Tenant(UUID id, String name, String slug, List<CnpjEntry> cnpjs, OffsetDateTime createdAt) {
}

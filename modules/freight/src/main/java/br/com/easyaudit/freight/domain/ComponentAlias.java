package br.com.easyaudit.freight.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Sinônimo de nome de componente de uma transportadora. {@code componentCode} é um dos códigos canônicos
 * ({@code FREIGHT_WEIGHT}, {@code AD_VALOREM}, {@code GRIS}, {@code TOLL}, {@code TDA}, {@code TDE},
 * {@code DISPATCH_FEE}, {@code ICMS} ou {@code OTHER}).
 */
public record ComponentAlias(UUID id, UUID carrierId, String alias, String componentCode, OffsetDateTime createdAt) {
}

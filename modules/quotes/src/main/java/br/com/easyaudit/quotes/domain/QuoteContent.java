package br.com.easyaudit.quotes.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;

/**
 * Conteúdo normalizado de uma cotação (uma revisão). O {@link #hash()} identifica o conteúdo: reenviar a mesma
 * cotação sem mudança dá o mesmo hash e não cria revisão.
 */
public record QuoteContent(String carrierCnpj, String service, BigDecimal amount, OffsetDateTime quotedAt,
        String destinationCep, BigDecimal weightKg, BigDecimal cargoValue, Integer deliveryDays, String nfeKey,
        String orderReference) {

    public String hash() {
        String canonical = String.join("|", carrierCnpj, nullToEmpty(service), amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                String.valueOf(quotedAt.toInstant().toEpochMilli()), destinationCep,
                weightKg == null ? "" : weightKg.setScale(3, RoundingMode.HALF_UP).toPlainString(),
                cargoValue == null ? "" : cargoValue.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                deliveryDays == null ? "" : deliveryDays.toString(), nullToEmpty(nfeKey), nullToEmpty(orderReference));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}

package br.com.easyaudit.settlement.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Modelos de leitura do lote de pagamento. */
public final class BatchModel {

    private BatchModel() {
    }

    public enum Status { OPEN, PAID, CANCELLED }

    public record Batch(UUID id, Status status, String carrierRoot, String carrierName, String description,
            LocalDate dueDate, int caseCount, BigDecimal chargedTotal, BigDecimal approvedTotal, LocalDate paidOn,
            String paymentReference, String paidBy, OffsetDateTime createdAt, String createdBy) {

        public BigDecimal savingsTotal() {
            return chargedTotal.subtract(approvedTotal);
        }
    }

    public record Item(UUID caseId, String cteAccessKey, String carrierCnpj, String carrierName, LocalDate emissionDate,
            BigDecimal chargedAmount, BigDecimal approvedAmount) {

        public BigDecimal savingsAmount() {
            return chargedAmount.subtract(approvedAmount);
        }

        /** Série do CT-e, extraída da chave de acesso (posições 23 a 25). */
        public String series() {
            return cteAccessKey.substring(22, 25);
        }

        /** Número do CT-e, extraído da chave de acesso (posições 26 a 34). */
        public String number() {
            return cteAccessKey.substring(25, 34);
        }
    }

    public record Export(UUID id, OffsetDateTime exportedAt, String exportedBy, int rowCount) {
    }

    public record Detail(Batch batch, List<Item> items, List<Export> exports) {
    }

    public record Page(List<Batch> items, int page, int size, long totalElements) {

        public int totalPages() {
            return (int) Math.ceil(totalElements / (double) size);
        }
    }
}

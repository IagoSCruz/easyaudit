package br.com.easyaudit.audit.api;

import java.util.List;
import java.util.UUID;

/**
 * Mudanças de estado que a liquidação faz nos casos, na mesma transação do lote de pagamento. Cada operação é
 * tudo-ou-nada: se algum caso não estiver no estado esperado (por exemplo, já {@code CANCELLED}), nada muda e a
 * {@link CaseCommandException} lista os casos com problema.
 */
public interface AuditCaseCommands {

    /** Casos {@code APPROVED} passam a {@code SCHEDULED_FOR_PAYMENT} e ficam associados ao lote. */
    void schedule(List<UUID> caseIds, UUID batchId, String actor);

    /** Casos {@code SCHEDULED_FOR_PAYMENT} voltam a {@code APPROVED} e saem do lote. */
    void unschedule(List<UUID> caseIds, String actor);

    /** Casos {@code SCHEDULED_FOR_PAYMENT} passam a {@code PAID}. */
    void markPaid(List<UUID> caseIds, String actor);

    /** Caso que não pôde sofrer a operação, com o estado em que está ({@code null} se não existe). */
    record Rejected(UUID caseId, String currentStatus) {
    }

    class CaseCommandException extends RuntimeException {

        private final transient List<Rejected> rejected;

        public CaseCommandException(String message, List<Rejected> rejected) {
            super(message);
            this.rejected = List.copyOf(rejected);
        }

        public List<Rejected> rejected() {
            return rejected;
        }

        /** Algum dos casos rejeitados está {@code CANCELLED}. */
        public boolean hasCancelled() {
            return rejected.stream().anyMatch(r -> "CANCELLED".equals(r.currentStatus()));
        }
    }
}

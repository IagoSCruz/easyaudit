package br.com.easyaudit.platform.messaging;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Idempotência por consumidor: grava {@code (consumer, event_id)} em {@code messaging.processed_event} na mesma
 * transação do efeito. Se o par já existe, o efeito não roda de novo (entrega duplicada).
 */
public class IdempotentConsumer {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public IdempotentConsumer(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** @return {@code true} se o efeito rodou, {@code false} se o evento já tinha sido processado */
    public boolean runOnce(String consumer, UUID eventId, Runnable effect) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            int inserted = jdbc.sql("""
                    INSERT INTO messaging.processed_event (consumer, event_id) VALUES (:consumer, :eventId)
                    ON CONFLICT DO NOTHING""")
                    .param("consumer", consumer)
                    .param("eventId", eventId)
                    .update();
            if (inserted == 0) {
                return false;
            }
            effect.run();
            return true;
        }));
    }
}

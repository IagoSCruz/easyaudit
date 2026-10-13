-- Idempotência dos consumidores: (consumidor, evento) gravado na mesma transação do efeito.
CREATE TABLE messaging.processed_event (
    consumer     text        NOT NULL,
    event_id     uuid        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

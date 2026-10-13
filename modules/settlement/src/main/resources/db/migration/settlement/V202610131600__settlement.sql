-- Liquidação: lotes de pagamento por transportadora (raiz do CNPJ), itens e exportações.

CREATE TABLE settlement.payment_batch (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    carrier_root      char(8)     NOT NULL,
    carrier_name      text,
    description       text,
    due_date          date        NOT NULL,
    status            text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'PAID', 'CANCELLED')),
    case_count        integer     NOT NULL DEFAULT 0 CHECK (case_count >= 0),
    charged_total     numeric(15, 2) NOT NULL DEFAULT 0,
    approved_total    numeric(15, 2) NOT NULL DEFAULT 0,
    paid_on           date,
    payment_reference text,
    paid_by           text,
    created_by        text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    -- um lote pago tem data e referência; os demais não
    CONSTRAINT payment_batch_paid_check CHECK ((status = 'PAID') = (paid_on IS NOT NULL AND payment_reference IS NOT NULL))
);
CREATE INDEX payment_batch_status_idx ON settlement.payment_batch (tenant_id, status, due_date);
CREATE INDEX payment_batch_carrier_idx ON settlement.payment_batch (tenant_id, carrier_root, due_date);

-- Itens do lote. Os valores são a fotografia do caso no momento da inclusão. Item retirado (ou de lote cancelado)
-- fica com `released_at` preenchido e deixa de contar; um caso só pode estar em um lote ativo por vez.
CREATE TABLE settlement.batch_item (
    batch_id         uuid        NOT NULL REFERENCES settlement.payment_batch (id),
    case_id          uuid        NOT NULL,
    tenant_id        uuid        NOT NULL,
    cte_access_key   char(44)    NOT NULL,
    carrier_cnpj     char(14)    NOT NULL,
    carrier_name     text,
    emission_date    date,
    charged_amount   numeric(15, 2) NOT NULL,
    approved_amount  numeric(15, 2) NOT NULL,
    added_at         timestamptz NOT NULL DEFAULT now(),
    released_at      timestamptz,
    release_reason   text CHECK (release_reason IN ('REMOVED', 'BATCH_CANCELLED', 'CASE_CANCELLED')),
    PRIMARY KEY (batch_id, case_id),
    CONSTRAINT batch_item_release_check CHECK ((released_at IS NULL) = (release_reason IS NULL))
);
CREATE UNIQUE INDEX batch_item_active_case_idx ON settlement.batch_item (tenant_id, case_id) WHERE released_at IS NULL;

CREATE TABLE settlement.batch_export (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    batch_id    uuid        NOT NULL REFERENCES settlement.payment_batch (id),
    exported_at timestamptz NOT NULL DEFAULT now(),
    exported_by text        NOT NULL,
    row_count   integer     NOT NULL
);
CREATE INDEX batch_export_batch_idx ON settlement.batch_export (tenant_id, batch_id, exported_at);

SELECT platform.enable_tenant_rls('settlement.payment_batch');
SELECT platform.enable_tenant_rls('settlement.batch_item');
SELECT platform.enable_tenant_rls('settlement.batch_export');

-- o registro das exportações não é alterado nem apagado
REVOKE UPDATE, DELETE ON settlement.batch_export FROM easyaudit_app;

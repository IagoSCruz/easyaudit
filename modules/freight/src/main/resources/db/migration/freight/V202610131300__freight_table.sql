-- Tabelas de frete: metadados em colunas e o conteúdo validado (formato canônico) em jsonb. Uma versão ACTIVE é
-- imutável; mudar a tabela é criar uma nova versão (DRAFT) a partir dela.
CREATE TABLE freight.freight_table (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    carrier_id    uuid        NOT NULL REFERENCES freight.carrier (id),
    name          text        NOT NULL,
    version       integer     NOT NULL CHECK (version >= 1),
    status        text        NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    valid_from    date        NOT NULL,
    valid_to      date,
    includes_icms boolean     NOT NULL,
    content       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    activated_at  timestamptz,
    archived_at   timestamptz,
    CONSTRAINT freight_table_version_unique UNIQUE (tenant_id, carrier_id, version),
    CONSTRAINT freight_table_period_valid CHECK (valid_to IS NULL OR valid_to >= valid_from)
);
CREATE INDEX freight_table_carrier_status_idx ON freight.freight_table (tenant_id, carrier_id, status, valid_from);

SELECT platform.enable_tenant_rls('freight.freight_table');

-- Cotações de frete (checkout) e o histórico de revisões. A cotação é identificada por (tenant, origem, id externo).
-- O vínculo com a NF-e guarda só o id do documento: sem chave estrangeira para outro schema.
CREATE TABLE quotes.quote (
    id                     uuid        PRIMARY KEY,
    tenant_id              uuid        NOT NULL,
    source                 text        NOT NULL CHECK (source IN ('VTEX', 'INTELIPOST', 'FRETE_RAPIDO', 'OTHER')),
    external_id            text        NOT NULL,
    carrier_cnpj           char(14)    NOT NULL,
    nfe_key                char(44),
    order_reference        text,
    link_status            text        NOT NULL DEFAULT 'UNLINKED'
                           CHECK (link_status IN ('UNLINKED', 'LINKED', 'AMBIGUOUS_ORDER_REFERENCE')),
    link_method            text        CHECK (link_method IN ('KEY', 'ORDER')),
    linked_nfe_document_id uuid,
    current_revision       integer     NOT NULL DEFAULT 1,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT quote_tenant_source_external_unique UNIQUE (tenant_id, source, external_id)
);
CREATE INDEX quote_nfe_key_idx ON quotes.quote (tenant_id, nfe_key) WHERE nfe_key IS NOT NULL;
CREATE INDEX quote_order_reference_idx ON quotes.quote (tenant_id, order_reference) WHERE order_reference IS NOT NULL;
CREATE INDEX quote_linked_doc_idx ON quotes.quote (tenant_id, linked_nfe_document_id) WHERE linked_nfe_document_id IS NOT NULL;
CREATE INDEX quote_status_idx ON quotes.quote (tenant_id, link_status, created_at DESC);

CREATE TABLE quotes.quote_revision (
    id              uuid         PRIMARY KEY,
    tenant_id       uuid         NOT NULL,
    quote_id        uuid         NOT NULL REFERENCES quotes.quote (id),
    revision        integer      NOT NULL,
    carrier_cnpj    char(14)     NOT NULL,
    service         text,
    amount          numeric(15, 2) NOT NULL,
    quoted_at       timestamptz  NOT NULL,
    destination_cep char(8)      NOT NULL,
    weight_kg       numeric(12, 3),
    cargo_value     numeric(15, 2),
    delivery_days   integer,
    nfe_key         char(44),
    order_reference text,
    content_hash    char(64)     NOT NULL,
    received_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT quote_revision_unique UNIQUE (quote_id, revision)
);
CREATE INDEX quote_revision_tenant_idx ON quotes.quote_revision (tenant_id, quote_id);

SELECT platform.enable_tenant_rls('quotes.quote');
SELECT platform.enable_tenant_rls('quotes.quote_revision');

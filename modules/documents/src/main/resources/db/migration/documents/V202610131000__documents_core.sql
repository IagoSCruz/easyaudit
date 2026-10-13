-- Documentos fiscais recebidos (CT-e e NF-e), o XML original e os eventos de cancelamento.
CREATE TABLE documents.document (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    kind           text        NOT NULL CHECK (kind IN ('CTE', 'NFE')),
    access_key     char(44)    NOT NULL,
    status         text        NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED')),
    lifecycle      text        NOT NULL DEFAULT 'ACTIVE' CHECK (lifecycle IN ('ACTIVE', 'CANCELLED', 'SUBSTITUTED')),
    failure_reason text,
    source         text        NOT NULL CHECK (source IN ('UPLOAD', 'WEBHOOK', 'SEFAZ_DFE')),
    issued_at      timestamptz,
    canonical      jsonb,
    raw_sha256     char(64)    NOT NULL,
    received_at    timestamptz NOT NULL DEFAULT now(),
    processed_at   timestamptz,
    updated_at     timestamptz NOT NULL DEFAULT now(),
    -- idempotência: a mesma chave pode existir em tenants diferentes, mas uma vez por tenant e tipo
    CONSTRAINT document_tenant_key_unique UNIQUE (tenant_id, kind, access_key)
);
CREATE INDEX document_tenant_received_idx ON documents.document (tenant_id, received_at DESC);
CREATE INDEX document_tenant_status_idx ON documents.document (tenant_id, status, received_at DESC);
CREATE INDEX document_tenant_kind_idx ON documents.document (tenant_id, kind, received_at DESC);

-- Bytes originais, sem alteração. Atrás da porta RawDocumentStorage (hoje PostgreSQL; S3 numa change futura).
CREATE TABLE documents.raw_document (
    document_id uuid    PRIMARY KEY REFERENCES documents.document (id),
    tenant_id   uuid    NOT NULL,
    content     bytea   NOT NULL,
    size_bytes  integer NOT NULL
);
CREATE INDEX raw_document_tenant_idx ON documents.raw_document (tenant_id);

-- Eventos de cancelamento aceitos. Ficam pendentes (applied_at nulo) até existir o documento do tenant.
CREATE TABLE documents.pending_cancellation (
    id          uuid        PRIMARY KEY,
    tenant_id   uuid        NOT NULL,
    target_kind text        NOT NULL CHECK (target_kind IN ('CTE', 'NFE')),
    access_key  char(44)    NOT NULL,
    event_type  text        NOT NULL,
    sequence    integer     NOT NULL,
    raw_sha256  char(64)    NOT NULL,
    content     bytea       NOT NULL,
    received_at timestamptz NOT NULL DEFAULT now(),
    applied_at  timestamptz,
    -- idempotência dos eventos: chave, tipo e sequência dentro do tenant
    CONSTRAINT pending_cancellation_unique UNIQUE (tenant_id, access_key, event_type, sequence)
);
CREATE INDEX pending_cancellation_lookup_idx ON documents.pending_cancellation (tenant_id, target_kind, access_key)
    WHERE applied_at IS NULL;

SELECT platform.enable_tenant_rls('documents.document');
SELECT platform.enable_tenant_rls('documents.raw_document');
SELECT platform.enable_tenant_rls('documents.pending_cancellation');

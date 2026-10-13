-- Credenciais de integração (webhooks HMAC). Fora da RLS: o lookup por key_id acontece antes de haver tenant no contexto.
-- O segredo precisa estar em claro para verificar o HMAC, então é cifrado (AES-256-GCM) com chave mestra do ambiente.
CREATE TABLE tenancy.integration_credential (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL REFERENCES tenancy.tenant (id),
    key_id            text        NOT NULL,
    description       text        NOT NULL,
    secret_ciphertext bytea       NOT NULL,
    key_version       integer     NOT NULL,
    status            text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    revoked_at        timestamptz,
    CONSTRAINT integration_credential_key_id_key UNIQUE (key_id)
);
CREATE INDEX integration_credential_tenant_idx ON tenancy.integration_credential (tenant_id, created_at);

-- Tenants e seus CNPJs. Estas tabelas ficam fora da RLS (consultadas antes de existir um tenant no contexto).
CREATE TABLE tenancy.tenant (
    id         uuid        PRIMARY KEY,
    name       text        NOT NULL,
    slug       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_slug_key UNIQUE (slug)
);

-- Dono de cada raiz de CNPJ (8 primeiros caracteres): garante, atomicamente, que a raiz pertença a um só tenant.
CREATE TABLE tenancy.cnpj_root (
    root      text PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenancy.tenant (id)
);

-- Entradas do tenant: CNPJ completo (14) ou raiz (8).
CREATE TABLE tenancy.tenant_cnpj (
    id         uuid        PRIMARY KEY,
    tenant_id  uuid        NOT NULL REFERENCES tenancy.tenant (id),
    cnpj       text        NOT NULL,
    kind       text        NOT NULL CHECK (kind IN ('FULL', 'ROOT')),
    root       text        NOT NULL REFERENCES tenancy.cnpj_root (root),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT tenant_cnpj_unique UNIQUE (tenant_id, cnpj)
);
CREATE INDEX tenant_cnpj_root_idx ON tenancy.tenant_cnpj (root);

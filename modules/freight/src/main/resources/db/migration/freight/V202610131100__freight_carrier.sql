-- Transportadoras do tenant e seus sinônimos de nomes de componente de valor do CT-e.
CREATE TABLE freight.carrier (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    cnpj            char(14)    NOT NULL,
    name            text        NOT NULL,
    active          boolean     NOT NULL DEFAULT true,
    auto_registered boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT carrier_tenant_cnpj_unique UNIQUE (tenant_id, cnpj)
);

CREATE TABLE freight.component_alias (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    carrier_id       uuid        NOT NULL REFERENCES freight.carrier (id),
    alias_original   text        NOT NULL,
    alias_normalized text        NOT NULL,
    component_code   text        NOT NULL CHECK (component_code IN
        ('FREIGHT_WEIGHT', 'AD_VALOREM', 'GRIS', 'TOLL', 'TDA', 'TDE', 'DISPATCH_FEE', 'ICMS', 'OTHER')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT component_alias_unique UNIQUE (tenant_id, carrier_id, alias_normalized)
);
CREATE INDEX component_alias_carrier_idx ON freight.component_alias (tenant_id, carrier_id);

SELECT platform.enable_tenant_rls('freight.carrier');
SELECT platform.enable_tenant_rls('freight.component_alias');

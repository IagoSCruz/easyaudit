-- Auditoria de CT-es: políticas, casos, revisões imutáveis, índice de NF-es, histórico append-only e contestações.

-- Política por tenant (carrier_id nulo) ou por transportadora; campos nulos herdam da política do tenant.
CREATE TABLE audit.policy (
    id                            uuid        PRIMARY KEY,
    tenant_id                     uuid        NOT NULL,
    carrier_id                    uuid,
    absolute_tolerance            numeric(15, 2) CHECK (absolute_tolerance >= 0),
    percent_tolerance             numeric(7, 4)  CHECK (percent_tolerance >= 0 AND percent_tolerance <= 100),
    auto_approve                  boolean,
    weight_tolerance_percent      numeric(7, 4)  CHECK (weight_tolerance_percent >= 0 AND weight_tolerance_percent <= 100),
    cargo_value_tolerance_percent numeric(7, 4)  CHECK (cargo_value_tolerance_percent >= 0 AND cargo_value_tolerance_percent <= 100),
    created_at                    timestamptz NOT NULL DEFAULT now(),
    updated_at                    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT policy_scope_unique UNIQUE NULLS NOT DISTINCT (tenant_id, carrier_id)
);

CREATE TABLE audit.audit_case (
    id                uuid        PRIMARY KEY,
    tenant_id         uuid        NOT NULL,
    cte_document_id   uuid        NOT NULL,
    cte_access_key    char(44)    NOT NULL,
    cte_type          text        NOT NULL CHECK (cte_type IN ('NORMAL', 'COMPLEMENT', 'SUBSTITUTE')),
    status            text        NOT NULL DEFAULT 'PENDING_AUDIT' CHECK (status IN ('PENDING_AUDIT', 'COMPLIANT',
                          'DIVERGENT', 'NOT_AUDITABLE', 'DISPUTED', 'APPROVED', 'SCHEDULED_FOR_PAYMENT', 'PAID', 'CANCELLED')),
    last_classification text      CHECK (last_classification IN ('COMPLIANT', 'DIVERGENT', 'NOT_AUDITABLE')),
    version           bigint      NOT NULL DEFAULT 0,
    carrier_id        uuid,
    carrier_cnpj      char(14)    NOT NULL,
    carrier_name      text,
    emission_date     date,
    destination_uf    char(2),
    charged_amount    numeric(15, 2) NOT NULL,
    reference_amount  numeric(15, 2),
    divergence_amount numeric(15, 2),
    failed_checks     text[]      NOT NULL DEFAULT '{}',
    alerts            text[]      NOT NULL DEFAULT '{}',
    approved_amount   numeric(15, 2),
    cancel_reason     text,
    current_revision  integer     NOT NULL DEFAULT 0,
    batch_id          uuid,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT audit_case_cte_unique UNIQUE (tenant_id, cte_document_id)
);
CREATE INDEX audit_case_status_idx ON audit.audit_case (tenant_id, status, emission_date);
CREATE INDEX audit_case_carrier_idx ON audit.audit_case (tenant_id, carrier_id, emission_date);
CREATE INDEX audit_case_failed_checks_idx ON audit.audit_case USING gin (failed_checks);

-- Revisões da auditoria: snapshot imutável (a role da aplicação não tem UPDATE nem DELETE).
CREATE TABLE audit.audit_revision (
    id               uuid        PRIMARY KEY,
    tenant_id        uuid        NOT NULL,
    case_id          uuid        NOT NULL REFERENCES audit.audit_case (id),
    revision         integer     NOT NULL,
    trigger          text        NOT NULL,
    classification   text        NOT NULL CHECK (classification IN ('COMPLIANT', 'DIVERGENT', 'NOT_AUDITABLE')),
    charged_amount   numeric(15, 2) NOT NULL,
    reference_amount numeric(15, 2),
    snapshot         jsonb       NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT audit_revision_unique UNIQUE (case_id, revision)
);
CREATE INDEX audit_revision_tenant_idx ON audit.audit_revision (tenant_id, case_id);

-- NF-es referenciadas pelo CT-e do caso; serve à verificação de duplicidade.
CREATE TABLE audit.case_nfe (
    tenant_id    uuid        NOT NULL,
    case_id      uuid        NOT NULL REFERENCES audit.audit_case (id),
    nfe_key      char(44)    NOT NULL,
    carrier_root char(8)     NOT NULL,
    cte_type     text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (case_id, nfe_key)
);
CREATE INDEX case_nfe_key_idx ON audit.case_nfe (tenant_id, nfe_key);

-- Histórico append-only: a role da aplicação não tem UPDATE nem DELETE.
CREATE TABLE audit.case_transition (
    id              uuid        PRIMARY KEY,
    tenant_id       uuid        NOT NULL,
    case_id         uuid        NOT NULL REFERENCES audit.audit_case (id),
    from_status     text        NOT NULL,
    to_status       text        NOT NULL,
    actor           text        NOT NULL,
    action          text        NOT NULL,
    note            text,
    approved_amount numeric(15, 2),
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX case_transition_case_idx ON audit.case_transition (tenant_id, case_id, created_at);

CREATE TABLE audit.dispute (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    case_id        uuid        NOT NULL REFERENCES audit.audit_case (id),
    category       text        NOT NULL CHECK (category IN ('VALUE_ABOVE_REFERENCE', 'DUPLICATE_CHARGE',
                       'WEIGHT_DIVERGENCE', 'UNDUE_COMPONENT', 'OTHER')),
    description    text        NOT NULL,
    amount_due     numeric(15, 2) NOT NULL,
    status         text        NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'WITHDRAWN')),
    agreed_amount  numeric(15, 2),
    resolution_note text,
    created_by     text        NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    resolved_at    timestamptz
);
CREATE INDEX dispute_case_idx ON audit.dispute (tenant_id, case_id);

SELECT platform.enable_tenant_rls('audit.policy');
SELECT platform.enable_tenant_rls('audit.audit_case');
SELECT platform.enable_tenant_rls('audit.audit_revision');
SELECT platform.enable_tenant_rls('audit.case_nfe');
SELECT platform.enable_tenant_rls('audit.case_transition');
SELECT platform.enable_tenant_rls('audit.dispute');

-- imutabilidade garantida pelo banco
REVOKE UPDATE, DELETE ON audit.case_transition FROM easyaudit_app;
REVOKE UPDATE, DELETE ON audit.audit_revision FROM easyaudit_app;

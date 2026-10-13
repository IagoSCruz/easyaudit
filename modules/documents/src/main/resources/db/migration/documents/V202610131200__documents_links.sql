-- Vínculos entre documentos: CT-e → NF-e referenciadas, CT-e complementar → complementado, CT-e substituto →
-- substituído. O vínculo nasce pendente (resolved = false) e é resolvido quando o documento de destino é processado,
-- em qualquer ordem de chegada.
CREATE TABLE documents.document_link (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    from_document_id   uuid        NOT NULL REFERENCES documents.document (id),
    link_type          text        NOT NULL CHECK (link_type IN ('CTE_NFE', 'COMPLEMENTS', 'SUBSTITUTES')),
    target_kind        text        NOT NULL CHECK (target_kind IN ('CTE', 'NFE')),
    target_key         char(44)    NOT NULL,
    target_document_id uuid        REFERENCES documents.document (id),
    resolved           boolean     NOT NULL DEFAULT false,
    created_at         timestamptz NOT NULL DEFAULT now(),
    resolved_at        timestamptz,
    CONSTRAINT document_link_unique UNIQUE (tenant_id, from_document_id, link_type, target_key)
);
CREATE INDEX document_link_pending_idx ON documents.document_link (tenant_id, target_kind, target_key)
    WHERE NOT resolved;
CREATE INDEX document_link_target_idx ON documents.document_link (tenant_id, target_document_id);

SELECT platform.enable_tenant_rls('documents.document_link');

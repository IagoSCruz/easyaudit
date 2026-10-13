-- Schemas de cada módulo e do outbox/idempotência (messaging). Roda como easyaudit_owner.
-- Cada módulo só acessa o próprio schema; a role da aplicação nunca é dona das tabelas.
CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS messaging;
CREATE SCHEMA IF NOT EXISTS tenancy;
CREATE SCHEMA IF NOT EXISTS documents;
CREATE SCHEMA IF NOT EXISTS freight;
CREATE SCHEMA IF NOT EXISTS quotes;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS settlement;

DO $$
DECLARE
    s text;
BEGIN
    FOREACH s IN ARRAY ARRAY['messaging', 'tenancy', 'documents', 'freight', 'quotes', 'audit', 'settlement']
    LOOP
        EXECUTE format('GRANT USAGE ON SCHEMA %I TO easyaudit_app', s);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE easyaudit_owner IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO easyaudit_app', s);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE easyaudit_owner IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO easyaudit_app', s);
    END LOOP;
END
$$;

GRANT USAGE ON SCHEMA platform TO easyaudit_app;

-- Liga a RLS padrão de tenant em uma tabela com a coluna tenant_id:
--   SELECT platform.enable_tenant_rls('audit.audit_case');
-- A política lê app.tenant_id (definido no início da transação). Sem tenant no contexto, nenhuma linha é visível.
CREATE OR REPLACE FUNCTION platform.enable_tenant_rls(target regclass) RETURNS void
LANGUAGE plpgsql AS
$fn$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %s', target);
    EXECUTE format(
        'CREATE POLICY tenant_isolation ON %s USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
        target);
END
$fn$;

-- Índices das consultas do dashboard: ordenações da listagem, busca por número do CT-e e filtro por alerta.
CREATE INDEX audit_case_emission_idx ON audit.audit_case (tenant_id, emission_date DESC, id);
CREATE INDEX audit_case_charged_idx ON audit.audit_case (tenant_id, charged_amount DESC, id);
CREATE INDEX audit_case_divergence_idx ON audit.audit_case (tenant_id, divergence_amount DESC NULLS LAST, id);
CREATE INDEX audit_case_alerts_idx ON audit.audit_case USING gin (alerts);
-- número do CT-e: posições 26 a 34 da chave de acesso
CREATE INDEX audit_case_cte_number_idx ON audit.audit_case (tenant_id, (substr(cte_access_key::text, 26, 9)));

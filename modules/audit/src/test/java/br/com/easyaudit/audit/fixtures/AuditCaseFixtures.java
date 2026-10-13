package br.com.easyaudit.audit.fixtures;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Casos de auditoria gravados direto no banco (pelo superusuário, que ignora a RLS), para os testes dos módulos que
 * consomem a {@code api} do audit sem rodar o fluxo completo de auditoria.
 */
public final class AuditCaseFixtures {

    private AuditCaseFixtures() {
    }

    /** Caso {@code APPROVED} cobrado em {@code charged}, aprovado em {@code approved}. */
    public static UUID approved(JdbcTemplate admin, UUID tenant, String carrierCnpj, String carrierName, String charged,
            String approved) {
        return insert(admin, tenant, "APPROVED", carrierCnpj, carrierName, charged, approved);
    }

    public static UUID insert(JdbcTemplate admin, UUID tenant, String status, String carrierCnpj, String carrierName,
            String charged, String approved) {
        return aCase(admin, tenant).status(status).carrier(carrierCnpj, carrierName).charged(charged).approved(approved)
                .insert();
    }

    public static Builder aCase(JdbcTemplate admin, UUID tenant) {
        return new Builder(admin, tenant);
    }

    /** Monta um caso com padrões razoáveis: {@code APPROVED}, transportadora 11222333000181, emitido em 2026-10-10. */
    public static final class Builder {

        private final JdbcTemplate admin;
        private final UUID tenant;
        private String status = "APPROVED";
        private String classification = "COMPLIANT";
        private String carrierCnpj = "11222333000181";
        private String carrierName = "Rápidos";
        private UUID carrierId;
        private LocalDate emission = LocalDate.of(2026, 10, 10);
        private String uf;
        private String charged = "10.00";
        private String reference;
        private String divergence;
        private String approved;
        private String[] failedChecks = new String[0];
        private String[] alerts = new String[0];
        private String accessKey;

        private Builder(JdbcTemplate admin, UUID tenant) {
            this.admin = admin;
            this.tenant = tenant;
        }

        public Builder status(String v) { this.status = v; return this; }
        public Builder classification(String v) { this.classification = v; return this; }
        public Builder carrier(String cnpj, String name) { this.carrierCnpj = cnpj; this.carrierName = name; return this; }
        public Builder carrierId(UUID v) { this.carrierId = v; return this; }
        public Builder emission(LocalDate v) { this.emission = v; return this; }
        public Builder uf(String v) { this.uf = v; return this; }
        public Builder charged(String v) { this.charged = v; return this; }
        public Builder reference(String v) { this.reference = v; return this; }
        public Builder divergence(String v) { this.divergence = v; return this; }
        public Builder approved(String v) { this.approved = v; return this; }
        public Builder failedChecks(String... v) { this.failedChecks = v; return this; }
        public Builder alerts(String... v) { this.alerts = v; return this; }
        public Builder accessKey(String v) { this.accessKey = v; return this; }

        public UUID insert() {
            UUID id = UUID.randomUUID();
            // chave de 44 dígitos única: UF 35, AAMM 2610, CNPJ do emitente, modelo 57, série 001, número, tpEmis 1, código
            String number = String.format("%09d", Math.abs(id.getMostSignificantBits() % 1_000_000_000L));
            String key = accessKey != null ? accessKey
                    : "352610" + carrierCnpj + "57" + "001" + number + "1" + "12345678" + "0";
            admin.update("""
                    INSERT INTO audit.audit_case (id, tenant_id, cte_document_id, cte_access_key, cte_type, status,
                        last_classification, version, carrier_id, carrier_cnpj, carrier_name, emission_date, destination_uf,
                        charged_amount, reference_amount, divergence_amount, approved_amount, failed_checks, alerts,
                        current_revision)
                    VALUES (?, ?, ?, ?, 'NORMAL', ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?::text[], 1)""",
                    id, tenant, UUID.randomUUID(), key, status, classification, carrierId, carrierCnpj, carrierName,
                    emission, uf, new BigDecimal(charged), reference == null ? null : new BigDecimal(reference),
                    divergence == null ? null : new BigDecimal(divergence),
                    approved == null ? null : new BigDecimal(approved), array(failedChecks), array(alerts));
            return id;
        }

        private static String array(String[] values) {
            return "{" + String.join(",", values) + "}";
        }
    }
}

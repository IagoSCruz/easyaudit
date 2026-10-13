package br.com.easyaudit.audit.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Array;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.audit.domain.AuditInput.CteType;
import br.com.easyaudit.audit.domain.AuditOutcome.Classification;
import br.com.easyaudit.audit.domain.CaseStatus;
import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistência dos casos de auditoria, das revisões, do índice de NF-es e do histórico. */
@Repository
public class CaseRepository {

    static final String COLUMNS = """
            id, cte_document_id, cte_access_key, cte_type, status, last_classification, version, carrier_id, carrier_cnpj,
            carrier_name, emission_date, destination_uf, charged_amount, reference_amount, divergence_amount,
            failed_checks, alerts, approved_amount, cancel_reason, current_revision, batch_id, created_at""";

    private final JdbcClient jdbc;

    public CaseRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- casos

    /** Cria o caso do CT-e. @return o id novo, ou vazio se o tenant já tem caso para o documento */
    public Optional<UUID> insertIfAbsent(UUID cteDocumentId, String cteKey, CteType type, String carrierCnpj,
            BigDecimal chargedAmount) {
        return jdbc.sql("""
                INSERT INTO audit.audit_case (id, tenant_id, cte_document_id, cte_access_key, cte_type, carrier_cnpj, charged_amount)
                VALUES (:id, :tenant, :doc, :key, :type, :carrier, :charged)
                ON CONFLICT (tenant_id, cte_document_id) DO NOTHING
                RETURNING id""")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value()).param("doc", cteDocumentId)
                .param("key", cteKey).param("type", type.name()).param("carrier", carrierCnpj)
                .param("charged", chargedAmount).query(UUID.class).optional();
    }

    public Optional<StoredCase> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit.audit_case WHERE id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    public List<StoredCase> findAllByIds(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit.audit_case WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new)).query((rs, n) -> map(rs)).list();
    }

    /** Caso travado ({@code FOR UPDATE}) para serializar reauditorias e ações. */
    public Optional<StoredCase> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit.audit_case WHERE id = :id FOR UPDATE")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    public Optional<StoredCase> findByCteDocument(UUID cteDocumentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit.audit_case WHERE cte_document_id = :doc")
                .param("doc", cteDocumentId).query((rs, n) -> map(rs)).optional();
    }

    /** Resultado de uma auditoria gravado no caso (colunas desnormalizadas) e novo estado. */
    public void applyAudit(UUID id, CaseStatus status, Classification classification, UUID carrierId,
            String carrierName, LocalDate emissionDate, String destinationUf, BigDecimal charged, BigDecimal reference,
            BigDecimal divergence, List<String> failedChecks, List<String> alerts, int revision) {
        jdbc.sql("""
                UPDATE audit.audit_case SET status = :status, last_classification = :classification, carrier_id = :carrierId,
                    carrier_name = :carrierName, emission_date = :emission, destination_uf = :uf, charged_amount = :charged,
                    reference_amount = :reference, divergence_amount = :divergence, failed_checks = :failed, alerts = :alerts,
                    current_revision = :revision, version = version + 1, updated_at = now()
                WHERE id = :id""")
                .param("id", id).param("status", status.name()).param("classification", classification.name())
                .param("carrierId", carrierId).param("carrierName", carrierName).param("emission", emissionDate)
                .param("uf", destinationUf).param("charged", charged).param("reference", reference)
                .param("divergence", divergence).param("failed", failedChecks.toArray(String[]::new))
                .param("alerts", alerts.toArray(String[]::new)).param("revision", revision).update();
    }

    /** Muda só o estado (e o valor aprovado), incrementando a versão. */
    public void changeStatus(UUID id, CaseStatus status, BigDecimal approvedAmount, String cancelReason) {
        jdbc.sql("""
                UPDATE audit.audit_case SET status = :status, approved_amount = :approved,
                    cancel_reason = COALESCE(:cancelReason, cancel_reason), version = version + 1, updated_at = now()
                WHERE id = :id""")
                .param("id", id).param("status", status.name()).param("approved", approvedAmount)
                .param("cancelReason", cancelReason).update();
    }

    /** Associa (ou desassocia, com {@code null}) o caso ao lote de pagamento. */
    public void setBatch(UUID id, UUID batchId) {
        jdbc.sql("UPDATE audit.audit_case SET batch_id = :batch, version = version + 1, updated_at = now() WHERE id = :id")
                .param("id", id).param("batch", batchId).update();
    }

    /** Acrescenta o alerta, se ainda não existir. */
    public void addAlert(UUID id, String alert) {
        jdbc.sql("""
                UPDATE audit.audit_case SET alerts = array_append(alerts, CAST(:alert AS text)), version = version + 1,
                    updated_at = now()
                WHERE id = :id AND NOT (CAST(:alert AS text) = ANY(alerts))""")
                .param("id", id).param("alert", alert).update();
    }

    // ---- contestações

    public void insertDispute(UUID caseId, String category, String description, BigDecimal amountDue, String actor) {
        jdbc.sql("""
                INSERT INTO audit.dispute (id, tenant_id, case_id, category, description, amount_due, created_by)
                VALUES (:id, :tenant, :case, :category, :description, :amountDue, :actor)""")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value()).param("case", caseId)
                .param("category", category).param("description", description).param("amountDue", amountDue)
                .param("actor", actor).update();
    }

    /** Encerra a contestação aberta do caso: {@code RESOLVED} (com valor acordado) ou {@code WITHDRAWN}. */
    public void closeDispute(UUID caseId, String status, BigDecimal agreedAmount, String note) {
        jdbc.sql("""
                UPDATE audit.dispute SET status = :status, agreed_amount = :agreed, resolution_note = :note,
                    resolved_at = now()
                WHERE case_id = :case AND status = 'OPEN'""")
                .param("case", caseId).param("status", status).param("agreed", agreedAmount).param("note", note).update();
    }

    // ---- revisões

    public UUID insertRevision(UUID caseId, int revision, String trigger, Classification classification,
            BigDecimal charged, BigDecimal reference, String snapshotJson) {
        UUID id = UuidV7.next();
        jdbc.sql("""
                INSERT INTO audit.audit_revision (id, tenant_id, case_id, revision, trigger, classification, charged_amount,
                    reference_amount, snapshot)
                VALUES (:id, :tenant, :case, :revision, :trigger, :classification, :charged, :reference, CAST(:snapshot AS jsonb))""")
                .param("id", id).param("tenant", TenantContext.require().value()).param("case", caseId)
                .param("revision", revision).param("trigger", trigger).param("classification", classification.name())
                .param("charged", charged).param("reference", reference).param("snapshot", snapshotJson).update();
        return id;
    }

    // ---- NF-es do caso e duplicidade

    /** Trava, em ordem crescente, as chaves de NF-e para evitar que dois CT-es da mesma NF-e passem juntos. */
    public void lockNfeKeys(List<String> keys) {
        String tenant = TenantContext.require().toString();
        keys.stream().sorted().forEach(key -> jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:k, 0))")
                .param("k", tenant + ":" + key).query((rs, n) -> 1).list());
    }

    public void replaceCaseNfes(UUID caseId, CteType type, String carrierRoot, List<String> keys) {
        jdbc.sql("DELETE FROM audit.case_nfe WHERE case_id = :case").param("case", caseId).update();
        for (String key : keys) {
            jdbc.sql("INSERT INTO audit.case_nfe (tenant_id, case_id, nfe_key, carrier_root, cte_type)"
                            + " VALUES (:tenant, :case, :key, :root, :type)")
                    .param("tenant", TenantContext.require().value()).param("case", caseId).param("key", key)
                    .param("root", carrierRoot).param("type", type.name()).update();
        }
    }

    /** Outro CT-e que referencia a NF-e. */
    public record OtherCte(UUID caseId, String cteKey, String nfeKey, String carrierRoot, boolean valid) {
    }

    /**
     * Casos NORMAIS anteriores a este (criados antes) que referenciam alguma das NF-es; {@code valid} = não cancelado.
     */
    public List<OtherCte> earlierCasesReferencing(UUID caseId, List<String> nfeKeys) {
        if (nfeKeys.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT c.id, c.cte_access_key, n.nfe_key, n.carrier_root, c.status <> 'CANCELLED' AS valid
                FROM audit.case_nfe n JOIN audit.audit_case c ON c.id = n.case_id
                JOIN audit.audit_case me ON me.id = :me
                WHERE n.nfe_key IN (:keys) AND n.case_id <> :me AND n.cte_type = 'NORMAL'
                  AND (c.created_at < me.created_at OR (c.created_at = me.created_at AND c.id < me.id))
                ORDER BY c.created_at, c.id""")
                .param("me", caseId).param("keys", nfeKeys)
                .query((rs, n) -> new OtherCte(rs.getObject(1, UUID.class), rs.getString(2).trim(), rs.getString(3).trim(),
                        rs.getString(4).trim(), rs.getBoolean(5))).list();
    }

    // ---- consultas para a reauditoria

    /** Casos que referenciam a NF-e (pela chave), nos estados auditáveis. */
    public List<UUID> auditableCasesReferencing(String nfeKey) {
        return jdbc.sql("""
                SELECT c.id FROM audit.case_nfe n JOIN audit.audit_case c ON c.id = n.case_id
                WHERE n.nfe_key = :key AND c.status IN ('PENDING_AUDIT', 'COMPLIANT', 'DIVERGENT', 'NOT_AUDITABLE')
                ORDER BY c.created_at, c.id""")
                .param("key", nfeKey).query(UUID.class).list();
    }

    /**
     * Página de casos auditáveis da transportadora (por CNPJ completo) emitidos no período [from, to], cuja última
     * revisão é anterior a {@code staleBefore} (evita repetir o trabalho se o evento for reentregue). Paginação por
     * chave: ids maiores que {@code afterId}.
     */
    public List<UUID> auditablePageOfCarrier(String carrierCnpj, LocalDate from, LocalDate to,
            OffsetDateTime staleBefore, UUID afterId, int limit) {
        return jdbc.sql("""
                SELECT c.id FROM audit.audit_case c
                WHERE c.carrier_cnpj = :cnpj AND c.status IN ('PENDING_AUDIT', 'COMPLIANT', 'DIVERGENT', 'NOT_AUDITABLE')
                  AND c.emission_date >= :from AND (CAST(:to AS date) IS NULL OR c.emission_date <= :to)
                  AND c.id > :after
                  AND NOT EXISTS (SELECT 1 FROM audit.audit_revision r WHERE r.case_id = c.id
                                  AND r.revision = c.current_revision AND r.created_at >= :stale)
                ORDER BY c.id LIMIT :limit""")
                .param("cnpj", carrierCnpj).param("from", from).param("to", to).param("stale", staleBefore)
                .param("after", afterId == null ? new UUID(0L, 0L) : afterId).param("limit", limit)
                .query(UUID.class).list();
    }

    // ---- histórico

    public void insertTransition(UUID caseId, CaseStatus from, CaseStatus to, String actor, String action, String note,
            BigDecimal approvedAmount) {
        jdbc.sql("""
                INSERT INTO audit.case_transition (id, tenant_id, case_id, from_status, to_status, actor, action, note, approved_amount)
                VALUES (:id, :tenant, :case, :from, :to, :actor, :action, :note, :approved)""")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value()).param("case", caseId)
                .param("from", from.name()).param("to", to.name()).param("actor", actor).param("action", action)
                .param("note", note).param("approved", approvedAmount).update();
    }

    static StoredCase map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String classification = rs.getString("last_classification");
        return new StoredCase(rs.getObject("id", UUID.class), rs.getObject("cte_document_id", UUID.class),
                rs.getString("cte_access_key").trim(), CteType.valueOf(rs.getString("cte_type")),
                CaseStatus.valueOf(rs.getString("status")), classification == null ? null : Classification.valueOf(classification),
                rs.getLong("version"), rs.getObject("carrier_id", UUID.class), rs.getString("carrier_cnpj").trim(),
                rs.getString("carrier_name"), rs.getObject("emission_date", LocalDate.class),
                rs.getString("destination_uf") == null ? null : rs.getString("destination_uf").trim(),
                rs.getBigDecimal("charged_amount"), rs.getBigDecimal("reference_amount"),
                rs.getBigDecimal("divergence_amount"), array(rs.getArray("failed_checks")), array(rs.getArray("alerts")),
                rs.getBigDecimal("approved_amount"), rs.getString("cancel_reason"), rs.getInt("current_revision"),
                rs.getObject("batch_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class));
    }

    private static List<String> array(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object o : (Object[]) array.getArray()) {
            values.add((String) o);
        }
        return values;
    }
}

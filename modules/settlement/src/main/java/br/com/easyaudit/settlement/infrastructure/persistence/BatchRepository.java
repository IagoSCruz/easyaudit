package br.com.easyaudit.settlement.infrastructure.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.settlement.application.BatchModel.Batch;
import br.com.easyaudit.settlement.application.BatchModel.Export;
import br.com.easyaudit.settlement.application.BatchModel.Item;
import br.com.easyaudit.settlement.application.BatchModel.Status;
import br.com.easyaudit.shared.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistência dos lotes de pagamento, dos itens e das exportações. */
@Repository
public class BatchRepository {

    private static final String COLUMNS = """
            id, status, carrier_root, carrier_name, description, due_date, case_count, charged_total, approved_total,
            paid_on, payment_reference, paid_by, created_at, created_by""";

    private final JdbcClient jdbc;

    public BatchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(String carrierRoot, String carrierName, String description, LocalDate dueDate, String actor) {
        UUID id = UuidV7.next();
        jdbc.sql("""
                INSERT INTO settlement.payment_batch (id, tenant_id, carrier_root, carrier_name, description, due_date, created_by)
                VALUES (:id, :tenant, :root, :name, :description, :due, :actor)""")
                .param("id", id).param("tenant", TenantContext.require().value()).param("root", carrierRoot)
                .param("name", carrierName).param("description", description).param("due", dueDate)
                .param("actor", actor).update();
        return id;
    }

    public Optional<Batch> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM settlement.payment_batch WHERE id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    /** Lote travado ({@code FOR UPDATE}) para serializar as operações sobre ele. */
    public Optional<Batch> lock(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM settlement.payment_batch WHERE id = :id FOR UPDATE")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    public List<Batch> list(Status status, String carrierRoot, LocalDate dueFrom, LocalDate dueTo, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM settlement.payment_batch WHERE " + FILTER
                        + " ORDER BY due_date DESC, created_at DESC, id LIMIT :limit OFFSET :offset")
                .param("status", status == null ? null : status.name()).param("root", carrierRoot)
                .param("from", dueFrom).param("to", dueTo).param("limit", limit).param("offset", offset)
                .query((rs, n) -> map(rs)).list();
    }

    public long count(Status status, String carrierRoot, LocalDate dueFrom, LocalDate dueTo) {
        return jdbc.sql("SELECT count(*) FROM settlement.payment_batch WHERE " + FILTER)
                .param("status", status == null ? null : status.name()).param("root", carrierRoot)
                .param("from", dueFrom).param("to", dueTo).query(Long.class).single();
    }

    private static final String FILTER = """
            (CAST(:status AS text) IS NULL OR status = :status)
            AND (CAST(:root AS text) IS NULL OR carrier_root = :root)
            AND (CAST(:from AS date) IS NULL OR due_date >= :from)
            AND (CAST(:to AS date) IS NULL OR due_date <= :to)""";

    // ---- itens

    public void insertItem(UUID batchId, UUID caseId, String cteKey, String carrierCnpj, String carrierName,
            LocalDate emissionDate, java.math.BigDecimal charged, java.math.BigDecimal approved) {
        jdbc.sql("""
                INSERT INTO settlement.batch_item (batch_id, case_id, tenant_id, cte_access_key, carrier_cnpj, carrier_name,
                    emission_date, charged_amount, approved_amount)
                VALUES (:batch, :case, :tenant, :key, :cnpj, :name, :emission, :charged, :approved)""")
                .param("batch", batchId).param("case", caseId).param("tenant", TenantContext.require().value())
                .param("key", cteKey).param("cnpj", carrierCnpj).param("name", carrierName)
                .param("emission", emissionDate).param("charged", charged).param("approved", approved).update();
    }

    public List<Item> activeItems(UUID batchId) {
        return jdbc.sql("""
                SELECT case_id, cte_access_key, carrier_cnpj, carrier_name, emission_date, charged_amount, approved_amount
                FROM settlement.batch_item WHERE batch_id = :batch AND released_at IS NULL
                ORDER BY emission_date, cte_access_key""")
                .param("batch", batchId).query(ITEM).list();
    }

    private static final org.springframework.jdbc.core.RowMapper<Item> ITEM = (rs, n) -> new Item(
            rs.getObject("case_id", UUID.class), rs.getString("cte_access_key").trim(),
            rs.getString("carrier_cnpj").trim(), rs.getString("carrier_name"),
            rs.getObject("emission_date", LocalDate.class), rs.getBigDecimal("charged_amount"),
            rs.getBigDecimal("approved_amount"));

    /** Itens para exibição e exportação: os ativos e, em lote cancelado, os liberados pelo cancelamento. */
    public List<Item> displayItems(UUID batchId) {
        return jdbc.sql("""
                SELECT case_id, cte_access_key, carrier_cnpj, carrier_name, emission_date, charged_amount, approved_amount
                FROM settlement.batch_item
                WHERE batch_id = :batch AND (released_at IS NULL OR release_reason = 'BATCH_CANCELLED')
                ORDER BY emission_date, cte_access_key""")
                .param("batch", batchId).query(ITEM).list();
    }

    /** Libera o item ativo do caso no lote. @return {@code true} se havia item ativo */
    public boolean releaseItem(UUID batchId, UUID caseId, String reason) {
        return jdbc.sql("""
                UPDATE settlement.batch_item SET released_at = now(), release_reason = :reason
                WHERE batch_id = :batch AND case_id = :case AND released_at IS NULL""")
                .param("batch", batchId).param("case", caseId).param("reason", reason).update() > 0;
    }

    public void releaseAll(UUID batchId, String reason) {
        jdbc.sql("UPDATE settlement.batch_item SET released_at = now(), release_reason = :reason"
                + " WHERE batch_id = :batch AND released_at IS NULL")
                .param("batch", batchId).param("reason", reason).update();
    }

    /** Lote {@code OPEN} que tem o caso como item ativo. */
    public Optional<UUID> openBatchOfCase(UUID caseId) {
        return jdbc.sql("""
                SELECT i.batch_id FROM settlement.batch_item i JOIN settlement.payment_batch b ON b.id = i.batch_id
                WHERE i.case_id = :case AND i.released_at IS NULL AND b.status = 'OPEN'""")
                .param("case", caseId).query(UUID.class).optional();
    }

    /** Recalcula a quantidade e os totais do lote a partir dos itens ativos. */
    public void recalculate(UUID batchId) {
        jdbc.sql("""
                UPDATE settlement.payment_batch b SET
                    case_count = s.n, charged_total = s.charged, approved_total = s.approved, updated_at = now()
                FROM (SELECT count(*)::int AS n, COALESCE(sum(charged_amount), 0) AS charged,
                             COALESCE(sum(approved_amount), 0) AS approved
                      FROM settlement.batch_item WHERE batch_id = :batch AND released_at IS NULL) s
                WHERE b.id = :batch""").param("batch", batchId).update();
    }

    // ---- estado do lote

    public void markPaid(UUID batchId, LocalDate paidOn, String reference, String actor) {
        jdbc.sql("""
                UPDATE settlement.payment_batch SET status = 'PAID', paid_on = :paidOn, payment_reference = :reference,
                    paid_by = :actor, updated_at = now() WHERE id = :id""")
                .param("id", batchId).param("paidOn", paidOn).param("reference", reference).param("actor", actor).update();
    }

    public void markCancelled(UUID batchId) {
        jdbc.sql("UPDATE settlement.payment_batch SET status = 'CANCELLED', updated_at = now() WHERE id = :id")
                .param("id", batchId).update();
    }

    // ---- exportações

    public void insertExport(UUID batchId, String actor, int rowCount) {
        jdbc.sql("INSERT INTO settlement.batch_export (id, tenant_id, batch_id, exported_by, row_count)"
                + " VALUES (:id, :tenant, :batch, :actor, :rows)")
                .param("id", UuidV7.next()).param("tenant", TenantContext.require().value()).param("batch", batchId)
                .param("actor", actor).param("rows", rowCount).update();
    }

    public List<Export> exports(UUID batchId) {
        return jdbc.sql("SELECT id, exported_at, exported_by, row_count FROM settlement.batch_export"
                        + " WHERE batch_id = :batch ORDER BY exported_at, id")
                .param("batch", batchId).query((rs, n) -> new Export(rs.getObject("id", UUID.class),
                        rs.getObject("exported_at", java.time.OffsetDateTime.class), rs.getString("exported_by"),
                        rs.getInt("row_count"))).list();
    }

    private static Batch map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Batch(rs.getObject("id", UUID.class), Status.valueOf(rs.getString("status")),
                rs.getString("carrier_root").trim(), rs.getString("carrier_name"), rs.getString("description"),
                rs.getObject("due_date", LocalDate.class), rs.getInt("case_count"), rs.getBigDecimal("charged_total"),
                rs.getBigDecimal("approved_total"), rs.getObject("paid_on", LocalDate.class),
                rs.getString("payment_reference"), rs.getString("paid_by"),
                rs.getObject("created_at", java.time.OffsetDateTime.class), rs.getString("created_by"));
    }
}

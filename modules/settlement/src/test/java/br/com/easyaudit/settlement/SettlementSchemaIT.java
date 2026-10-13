package br.com.easyaudit.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import br.com.easyaudit.platform.db.TenantRlsChecker;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

class SettlementSchemaIT extends AbstractSettlementIT {

    @Autowired
    JdbcTemplate app;
    @Autowired
    TransactionTemplate tx;

    private UUID insertBatch(java.util.function.Supplier<String> tenant) throws Exception {
        UUID id = UUID.randomUUID();
        TenantContext.callAs(TwoTenants.A, () -> tx.execute(s -> app.update("""
                insert into settlement.payment_batch (id, tenant_id, carrier_root, due_date, created_by)
                values (?, ?::uuid, '11222333', date '2026-11-10', 'ana')""", id, tenant.get())));
        return id;
    }

    @Test
    void migrationsApplyAndEveryTenantTableHasForcedRls() {
        String url = environment.getRequiredProperty("spring.datasource.url");
        assertThat(new TenantRlsChecker(new DriverManagerDataSource(url, "easyaudit_owner", "easyaudit_owner")).violations())
                .isEmpty();
        assertThat(admin.queryForObject("""
                select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'settlement' and c.relkind = 'r' and c.relforcerowsecurity""", Integer.class))
                .isEqualTo(3);
    }

    @Test
    void rlsHidesBatchesOfOtherTenantsAndRejectsForeignInserts() throws Exception {
        insertBatch(TwoTenants.A::toString);
        Integer seenByA = TenantContext.callAs(TwoTenants.A, () -> tx.execute(s ->
                app.queryForObject("select count(*) from settlement.payment_batch", Integer.class)));
        Integer seenByB = TenantContext.callAs(TwoTenants.B, () -> tx.execute(s ->
                app.queryForObject("select count(*) from settlement.payment_batch", Integer.class)));
        assertThat(seenByA).isEqualTo(1);
        assertThat(seenByB).isZero();
        // gravar com o tenant_id de outro tenant é recusado pela política
        assertThatThrownBy(() -> insertBatch(TwoTenants.B::toString)).rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void aCaseCanBeInOnlyOneActiveBatch() throws Exception {
        UUID b1 = insertBatch(TwoTenants.A::toString);
        UUID b2 = insertBatch(TwoTenants.A::toString);
        UUID caseId = UUID.randomUUID();
        String item = """
                insert into settlement.batch_item (batch_id, case_id, tenant_id, cte_access_key, carrier_cnpj,
                    charged_amount, approved_amount)
                values (?, ?, ?::uuid, '35261011222333000181570010000001231123456780', '11222333000181', 10, 10)""";
        TenantContext.callAs(TwoTenants.A, () -> tx.execute(s -> app.update(item, b1, caseId, TwoTenants.A.toString())));
        assertThatThrownBy(() -> TenantContext.callAs(TwoTenants.A,
                () -> tx.execute(s -> app.update(item, b2, caseId, TwoTenants.A.toString()))))
                .rootCause().hasMessageContaining("batch_item_active_case_idx");
        // liberado o primeiro, o caso pode entrar em outro lote
        admin.update("update settlement.batch_item set released_at = now(), release_reason = 'REMOVED' where batch_id = ?", b1);
        TenantContext.callAs(TwoTenants.A, () -> tx.execute(s -> app.update(item, b2, caseId, TwoTenants.A.toString())));
    }

    @Test
    void exportsCannotBeUpdatedOrDeletedByTheApplication() throws Exception {
        UUID batch = insertBatch(TwoTenants.A::toString);
        TenantContext.callAs(TwoTenants.A, () -> tx.execute(s -> app.update("""
                insert into settlement.batch_export (id, tenant_id, batch_id, exported_by, row_count)
                values (gen_random_uuid(), ?::uuid, ?, 'ana', 1)""", TwoTenants.A.toString(), batch)));
        for (String statement : new String[] {"update settlement.batch_export set row_count = 9",
                "delete from settlement.batch_export"}) {
            assertThatThrownBy(() -> TenantContext.callAs(TwoTenants.A, () -> tx.execute(s -> app.update(statement))))
                    .as(statement).rootCause().hasMessageContaining("permission denied");
        }
    }

    @Test
    void aPaidBatchNeedsDateAndReferenceAndOthersMustNotHaveThem() {
        UUID id = UUID.randomUUID();
        admin.update("insert into settlement.payment_batch (id, tenant_id, carrier_root, due_date, created_by)"
                + " values (?, ?, '11222333', date '2026-11-10', 'ana')", id, TwoTenants.A.value());
        assertThatThrownBy(() -> admin.update("update settlement.payment_batch set status = 'PAID' where id = ?", id))
                .rootCause().hasMessageContaining("payment_batch_paid_check");
        assertThatThrownBy(() -> admin.update(
                "update settlement.payment_batch set paid_on = date '2026-11-10', payment_reference = 'x' where id = ?", id))
                .rootCause().hasMessageContaining("payment_batch_paid_check");
    }
}

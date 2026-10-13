package br.com.easyaudit.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import br.com.easyaudit.audit.api.AuditCaseCommands;
import br.com.easyaudit.settlement.application.BatchModel;
import br.com.easyaudit.settlement.application.BatchModel.Detail;
import br.com.easyaudit.settlement.application.PaymentBatches;
import br.com.easyaudit.settlement.application.SettlementException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaymentBatchIT extends AbstractSettlementIT {

    static final LocalDate DUE = LocalDate.of(2026, 11, 10);

    @Autowired
    PaymentBatches batches;
    @Autowired
    AuditCaseCommands commands;

    private SettlementException failure(Callable<?> call) {
        try {
            asA(call);
        } catch (SettlementException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("exceção inesperada", e);
        }
        throw new AssertionError("era esperada uma SettlementException");
    }

    private Detail create(UUID... ids) throws Exception {
        return asA(() -> batches.create(DUE, "Lote de novembro", List.of(ids), "bia"));
    }

    private UUID[] threeApproved() {
        return new UUID[] {approved(CARRIER_X, "Rápidos", "103.00", "100.00"),
                approved(CARRIER_X, "Rápidos", "66.02", "66.02"), approved(CARRIER_X_BRANCH, "Rápidos Filial", "50.00", "45.00")};
    }

    // ---- 13.3 criação

    @Test
    void aValidBatchIsCreatedOpenWithTotalsAndTheCasesAreScheduled() throws Exception {
        UUID[] ids = threeApproved();

        Detail detail = create(ids);

        var batch = detail.batch();
        assertThat(batch.status()).isEqualTo(BatchModel.Status.OPEN);
        assertThat(batch.carrierRoot()).isEqualTo("11222333");
        assertThat(batch.dueDate()).isEqualTo(DUE);
        assertThat(batch.description()).isEqualTo("Lote de novembro");
        assertThat(batch.caseCount()).isEqualTo(3);
        assertThat(batch.chargedTotal()).isEqualByComparingTo("219.02");
        assertThat(batch.approvedTotal()).isEqualByComparingTo("211.02");
        assertThat(batch.savingsTotal()).isEqualByComparingTo("8.00");
        assertThat(batch.createdBy()).isEqualTo("bia");
        assertThat(detail.items()).hasSize(3);
        for (UUID id : ids) {
            assertThat(statusOf(id)).isEqualTo("SCHEDULED_FOR_PAYMENT");
            assertThat(admin.queryForObject("select batch_id from audit.audit_case where id = ?", UUID.class, id))
                    .isEqualTo(batch.id());
        }
        assertThat(admin.queryForList("select actor from audit.case_transition where action = 'SCHEDULE'", String.class))
                .containsOnly("bia");
    }

    @Test
    void anUnapprovedOrForeignCarrierCaseIs422ListingAllInvalidAndNothingChanges() throws Exception {
        UUID ok = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        UUID divergent = AuditCaseFixturesSupport.insert(admin, "DIVERGENT", CARRIER_X, "10.00", null);
        UUID otherCarrier = approved(CARRIER_Y, "Outra", "10.00", "10.00");
        UUID unknown = UUID.randomUUID();

        SettlementException e = failure(() -> batches.create(DUE, null, List.of(ok, divergent, otherCarrier, unknown), "bia"));

        assertThat(e.kind()).isEqualTo(SettlementException.Kind.INVALID);
        assertThat(e.issues()).extracting(SettlementException.Issue::field).containsExactlyInAnyOrder(
                "caseIds[" + divergent + "]", "caseIds[" + otherCarrier + "]", "caseIds[" + unknown + "]");
        assertThat(statusOf(ok)).isEqualTo("APPROVED");
        assertThat(admin.queryForObject("select count(*) from settlement.payment_batch", Integer.class)).isZero();
    }

    @Test
    void anEmptyListOrMissingDueDateIs422() {
        assertThat(failure(() -> batches.create(DUE, null, List.of(), "bia")).issues())
                .extracting(SettlementException.Issue::field).containsExactly("caseIds");
        UUID id = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        assertThat(failure(() -> batches.create(null, null, List.of(id), "bia")).issues())
                .extracting(SettlementException.Issue::field).containsExactly("dueDate");
    }

    @Test
    void aCaseAlreadyInABatchCannotGoInAnother() throws Exception {
        UUID id = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        create(id);
        SettlementException e = failure(() -> batches.create(DUE, null, List.of(id), "bia"));
        assertThat(e.kind()).isEqualTo(SettlementException.Kind.INVALID);
        assertThat(e.issues().get(0).message()).contains("SCHEDULED_FOR_PAYMENT");
    }

    @Test
    void casesOfOtherTenantsAreNotFound() throws Exception {
        UUID foreign = br.com.easyaudit.audit.fixtures.AuditCaseFixtures.approved(admin,
                br.com.easyaudit.platform.testsupport.TwoTenants.B.value(), CARRIER_X, "Rápidos", "10.00", "10.00");
        SettlementException e = failure(() -> batches.create(DUE, null, List.of(foreign), "bia"));
        assertThat(e.issues().get(0).message()).isEqualTo("Caso não encontrado.");
    }

    // ---- 13.4 manutenção

    @Test
    void removingACaseReturnsItToApprovedAndRecalculatesTheTotals() throws Exception {
        UUID[] ids = threeApproved();
        Detail created = create(ids);

        Detail after = asA(() -> batches.removeCase(created.batch().id(), ids[0], "bia"));

        assertThat(statusOf(ids[0])).isEqualTo("APPROVED");
        assertThat(admin.queryForObject("select batch_id from audit.audit_case where id = ?", UUID.class, ids[0])).isNull();
        assertThat(after.batch().caseCount()).isEqualTo(2);
        assertThat(after.batch().chargedTotal()).isEqualByComparingTo("116.02");
        assertThat(after.batch().approvedTotal()).isEqualByComparingTo("111.02");
        assertThat(after.batch().savingsTotal()).isEqualByComparingTo("5.00");
        assertThat(after.items()).extracting(BatchModel.Item::caseId).doesNotContain(ids[0]);
        // o caso retirado pode entrar em outro lote
        assertThat(create(ids[0]).batch().caseCount()).isEqualTo(1);
    }

    @Test
    void removingACaseThatIsNotInTheBatchIs404() throws Exception {
        Detail created = create(approved(CARRIER_X, "Rápidos", "10.00", "10.00"));
        assertThat(failure(() -> batches.removeCase(created.batch().id(), UUID.randomUUID(), "bia")).kind())
                .isEqualTo(SettlementException.Kind.NOT_FOUND);
    }

    @Test
    void addingCasesOfTheSameCarrierRecalculatesAndOtherCarriersAreRefused() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        Detail created = create(a);
        UUID b = approved(CARRIER_X_BRANCH, "Rápidos Filial", "20.00", "18.00");
        UUID other = approved(CARRIER_Y, "Outra", "5.00", "5.00");

        Detail after = asA(() -> batches.addCases(created.batch().id(), List.of(b), "bia"));
        assertThat(after.batch().caseCount()).isEqualTo(2);
        assertThat(after.batch().chargedTotal()).isEqualByComparingTo("30.00");
        assertThat(after.batch().approvedTotal()).isEqualByComparingTo("28.00");
        assertThat(statusOf(b)).isEqualTo("SCHEDULED_FOR_PAYMENT");

        SettlementException e = failure(() -> batches.addCases(created.batch().id(), List.of(other), "bia"));
        assertThat(e.kind()).isEqualTo(SettlementException.Kind.INVALID);
        assertThat(statusOf(other)).isEqualTo("APPROVED");
    }

    @Test
    void cancellingTheBatchReturnsTheCasesToApproved() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        UUID b = approved(CARRIER_X, "Rápidos", "20.00", "18.00");
        Detail created = create(a, b);

        Detail cancelled = asA(() -> batches.cancel(created.batch().id(), "bia"));

        assertThat(cancelled.batch().status()).isEqualTo(BatchModel.Status.CANCELLED);
        assertThat(statusOf(a)).isEqualTo("APPROVED");
        assertThat(statusOf(b)).isEqualTo("APPROVED");
        // os itens do lote cancelado continuam visíveis, e os casos podem entrar em outro lote
        assertThat(cancelled.items()).hasSize(2);
        assertThat(create(a, b).batch().status()).isEqualTo(BatchModel.Status.OPEN);
    }

    // ---- 13.6 baixa

    @Test
    void registeringThePaymentPaysTheBatchAndAllItsCases() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        UUID b = approved(CARRIER_X, "Rápidos", "20.00", "18.00");
        Detail created = create(a, b);
        LocalDate paidOn = LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo"));

        Detail paid = asA(() -> batches.registerPayment(created.batch().id(), paidOn, "PIX-E2E-123", "bia"));

        assertThat(paid.batch().status()).isEqualTo(BatchModel.Status.PAID);
        assertThat(paid.batch().paidOn()).isEqualTo(paidOn);
        assertThat(paid.batch().paymentReference()).isEqualTo("PIX-E2E-123");
        assertThat(paid.batch().paidBy()).isEqualTo("bia");
        assertThat(statusOf(a)).isEqualTo("PAID");
        assertThat(statusOf(b)).isEqualTo("PAID");
        assertThat(admin.queryForList("select actor from audit.case_transition where action = 'PAY'", String.class))
                .containsOnly("bia");
    }

    @Test
    void aFuturePaymentDateBlankReferenceOrEmptyBatchIs422() throws Exception {
        Detail created = create(approved(CARRIER_X, "Rápidos", "10.00", "10.00"));
        UUID id = created.batch().id();
        LocalDate tomorrow = LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")).plusDays(1);

        assertThat(failure(() -> batches.registerPayment(id, tomorrow, "x", "bia")).issues())
                .extracting(SettlementException.Issue::field).containsExactly("paidOn");
        assertThat(failure(() -> batches.registerPayment(id, DUE.minusDays(30), " ", "bia")).issues())
                .extracting(SettlementException.Issue::field).containsExactly("reference");
        asA(() -> batches.removeCase(id, created.items().get(0).caseId(), "bia"));
        assertThat(failure(() -> batches.registerPayment(id, DUE.minusDays(30), "x", "bia")).issues())
                .extracting(SettlementException.Issue::field).containsExactly("batch");
        assertThat(asA(() -> batches.get(id)).batch().status()).isEqualTo(BatchModel.Status.OPEN);
    }

    @Test
    void aPaidBatchIsImmutable() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        UUID extra = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        Detail created = create(a);
        UUID id = created.batch().id();
        asA(() -> batches.registerPayment(id, DUE.minusDays(30), "REF", "bia"));

        for (Callable<?> action : List.<Callable<?>>of(() -> batches.addCases(id, List.of(extra), "bia"),
                () -> batches.removeCase(id, a, "bia"), () -> batches.cancel(id, "bia"),
                () -> batches.registerPayment(id, DUE.minusDays(30), "REF2", "bia"))) {
            SettlementException e = failure(action);
            assertThat(e.kind()).isEqualTo(SettlementException.Kind.CONFLICT);
            assertThat(e.code()).isEqualTo("BATCH_NOT_OPEN");
        }
        assertThat(statusOf(a)).isEqualTo("PAID");
    }

    @Test
    void aCancelledCaseMakesThePaymentFailWith409AndNothingChanges() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        UUID b = approved(CARRIER_X, "Rápidos", "20.00", "18.00");
        Detail created = create(a, b);
        admin.update("update audit.audit_case set status = 'CANCELLED' where id = ?", b); // cancelado sem passar pelo evento

        SettlementException e = failure(() -> batches.registerPayment(created.batch().id(), DUE.minusDays(30), "REF", "bia"));

        assertThat(e.kind()).isEqualTo(SettlementException.Kind.CONFLICT);
        assertThat(e.code()).isEqualTo("CASE_CANCELLED");
        assertThat(statusOf(a)).isEqualTo("SCHEDULED_FOR_PAYMENT");
        assertThat(asA(() -> batches.get(created.batch().id())).batch().status()).isEqualTo(BatchModel.Status.OPEN);
    }

    // ---- 13.7 retirada de caso cancelado (a parte transacional; o pipeline por fila fica em CancelledCaseLeavesBatchIT)

    @Test
    void aCancelledCaseLeavesTheOpenBatchAndTotalsAreRecalculated() throws Exception {
        UUID[] ids = threeApproved();
        Detail created = create(ids);
        admin.update("update audit.audit_case set status = 'CANCELLED' where id = ?", ids[1]);

        asA(() -> {
            batches.onCaseCancelled(ids[1]);
            return null;
        });

        Detail after = asA(() -> batches.get(created.batch().id()));
        assertThat(after.batch().caseCount()).isEqualTo(2);
        assertThat(after.batch().chargedTotal()).isEqualByComparingTo("153.00");
        assertThat(after.items()).extracting(BatchModel.Item::caseId).containsExactlyInAnyOrder(ids[0], ids[2]);
        // repetir não muda nada
        asA(() -> {
            batches.onCaseCancelled(ids[1]);
            return null;
        });
        assertThat(asA(() -> batches.get(created.batch().id())).batch().caseCount()).isEqualTo(2);
    }

    @Test
    void aCancelledCaseOfAPaidBatchIsNotTouched() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "10.00", "10.00");
        Detail created = create(a);
        asA(() -> batches.registerPayment(created.batch().id(), DUE.minusDays(30), "REF", "bia"));
        asA(() -> {
            batches.onCaseCancelled(a);
            return null;
        });
        assertThat(asA(() -> batches.get(created.batch().id())).batch().caseCount()).isEqualTo(1);
    }

    // ---- 13.8 consulta

    @Test
    void listingFiltersByStatusCarrierAndDueDate() throws Exception {
        Detail open1 = create(approved(CARRIER_X, "Rápidos", "10.00", "10.00"));
        Detail toCancel = create(approved(CARRIER_X, "Rápidos", "10.00", "10.00"));
        asA(() -> batches.cancel(toCancel.batch().id(), "bia"));
        Detail otherCarrier = asA(() -> batches.create(DUE.plusDays(30), null,
                List.of(approved(CARRIER_Y, "Outra", "5.00", "5.00")), "bia"));
        Detail paid = create(approved(CARRIER_X, "Rápidos", "7.00", "7.00"));
        asA(() -> batches.registerPayment(paid.batch().id(), DUE.minusDays(30), "REF", "bia"));

        var open = asA(() -> batches.list(BatchModel.Status.OPEN, null, null, null, 0, 20));
        assertThat(open.items()).extracting(BatchModel.Batch::id).containsExactlyInAnyOrder(open1.batch().id(),
                otherCarrier.batch().id());
        assertThat(open.totalElements()).isEqualTo(2);
        assertThat(open.items()).allSatisfy(b -> assertThat(b.chargedTotal()).isNotNull());

        assertThat(asA(() -> batches.list(null, "99888777", null, null, 0, 20)).items())
                .extracting(BatchModel.Batch::id).containsExactly(otherCarrier.batch().id());
        assertThat(asA(() -> batches.list(null, null, DUE.plusDays(1), DUE.plusDays(60), 0, 20)).items())
                .extracting(BatchModel.Batch::id).containsExactly(otherCarrier.batch().id());
        assertThat(asA(() -> batches.list(null, null, null, null, 0, 2)).items()).hasSize(2);
        assertThat(asA(() -> batches.list(null, null, null, null, 1, 2)).items()).hasSize(2);
        assertThat(asA(() -> batches.list(null, null, null, null, 0, 20)).totalElements()).isEqualTo(4);
    }

    @Test
    void thePageSizeIsLimitedTo100() {
        assertThat(failure(() -> batches.list(null, null, null, null, 0, 101)).kind())
                .isEqualTo(SettlementException.Kind.INVALID);
    }

    @Test
    void anUnknownBatchIs404AndOtherTenantsCannotSeeIt() throws Exception {
        Detail created = create(approved(CARRIER_X, "Rápidos", "10.00", "10.00"));
        assertThat(failure(() -> batches.get(UUID.randomUUID())).kind()).isEqualTo(SettlementException.Kind.NOT_FOUND);
        try {
            br.com.easyaudit.platform.tenant.TenantContext.callAs(br.com.easyaudit.platform.testsupport.TwoTenants.B,
                    () -> batches.get(created.batch().id()));
            throw new AssertionError("o tenant B não deveria ver o lote");
        } catch (SettlementException e) {
            assertThat(e.kind()).isEqualTo(SettlementException.Kind.NOT_FOUND);
        }
    }

    /** Casos em estados que a fixture de {@code approved} não cobre. */
    static final class AuditCaseFixturesSupport {

        static UUID insert(org.springframework.jdbc.core.JdbcTemplate admin, String status, String cnpj, String charged,
                String approved) {
            return br.com.easyaudit.audit.fixtures.AuditCaseFixtures.insert(admin,
                    br.com.easyaudit.platform.testsupport.TwoTenants.A.value(), status, cnpj, "Rápidos", charged, approved);
        }
    }
}

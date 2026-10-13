package br.com.easyaudit.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import br.com.easyaudit.audit.api.AuditCaseCommands;
import br.com.easyaudit.audit.api.AuditCaseCommands.CaseCommandException;
import br.com.easyaudit.audit.application.AuditException;
import br.com.easyaudit.audit.application.CaseCancellation;
import br.com.easyaudit.audit.application.CaseWorkflow;
import br.com.easyaudit.audit.application.CaseWorkflow.BatchItem;
import br.com.easyaudit.audit.application.CaseWorkflow.DisputeCategory;
import br.com.easyaudit.audit.domain.AuditPolicy;
import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.audit.infrastructure.persistence.CaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class AuditWorkflowIT extends AuditFlowSupport {

    @Autowired
    CaseWorkflow workflow;
    @Autowired
    CaseCancellation cancellation;
    @Autowired
    AuditCaseCommands commands;
    @Autowired
    CaseRepository caseRepository;

    private int sequence = 1000;

    /** Caso conforme (cobrado 66.02) ou, com 80.00, divergente (referência 66.02). */
    private StoredCase newCase(String total) throws Exception {
        return audit(cteDocument(total)).orElseThrow();
    }

    /** CT-e com a sua própria NF-e (senão os CT-es do teste seriam todos duplicados da mesma NF-e). */
    private UUID cteDocument(String total) throws Exception {
        int n = sequence++;
        var nfe = br.com.easyaudit.documents.fixtures.NfeFixture.builder().number(n).volume(1, "10.000", "12.500").build();
        process(nfe);
        return process(cte().number(n).nfeKey(nfe.accessKey()).totalAmount(total).build());
    }

    private StoredCase compliant() throws Exception {
        return newCase("66.02");
    }

    private StoredCase divergent() throws Exception {
        return newCase("80.00");
    }

    private StoredCase reload(UUID id) throws Exception {
        return as(() -> tx.execute(t -> caseRepository.findById(id).orElseThrow()));
    }

    private <T> T asAuditor(Callable<T> call) throws Exception {
        return as(call);
    }

    private void autoApprove(boolean on) throws Exception {
        as(() -> policies.updateTenantPolicy(new AuditPolicy(new BigDecimal("0.00"), BigDecimal.ZERO, on,
                new BigDecimal("5"), new BigDecimal("1"))));
    }

    private AuditException failure(Callable<?> call) {
        try {
            as(call);
        } catch (AuditException e) {
            return e;
        } catch (Exception e) {
            throw new AssertionError("exceção inesperada", e);
        }
        throw new AssertionError("era esperada uma AuditException");
    }

    private List<String> history(UUID caseId) {
        return admin.queryForList("select from_status || '>' || to_status || ':' || action || ':' || actor"
                + " from audit.case_transition where case_id = ? order by created_at, id", String.class, caseId);
    }

    // ---- 12.3 aprovação automática

    @Test
    void autoApprovalOnApprovesACompliantCaseWithTheChargedAmountAndSystemActor() throws Exception {
        autoApprove(true);
        StoredCase c = compliant();

        assertThat(c.status().name()).isEqualTo("APPROVED");
        assertThat(c.approvedAmount()).isEqualByComparingTo("66.02");
        assertThat(history(c.id())).containsExactly("PENDING_AUDIT>COMPLIANT:AUDIT:SYSTEM",
                "COMPLIANT>APPROVED:AUTO_APPROVE:SYSTEM");
        assertThat(events("audit.status-changed")).hasSize(2);
        assertThat(events("audit.status-changed").get(1).getData()).containsEntry("toStatus", "APPROVED")
                .containsEntry("actor", "SYSTEM");
    }

    @Test
    void autoApprovalOffLeavesTheCompliantCaseWaitingForADecision() throws Exception {
        autoApprove(false);
        StoredCase c = compliant();
        assertThat(c.status().name()).isEqualTo("COMPLIANT");
        assertThat(c.approvedAmount()).isNull();
    }

    @Test
    void autoApprovalNeverApprovesADivergentCase() throws Exception {
        autoApprove(true);
        assertThat(divergent().status().name()).isEqualTo("DIVERGENT");
    }

    // ---- 12.4 ações manuais

    @Test
    void approvingACompliantCaseUsesTheChargedAmountWithoutJustification() throws Exception {
        StoredCase c = compliant();
        StoredCase approved = asAuditor(() -> workflow.approve(c.id(), c.version(), null, null, "ana"));
        assertThat(approved.status().name()).isEqualTo("APPROVED");
        assertThat(approved.approvedAmount()).isEqualByComparingTo("66.02");
        assertThat(approved.version()).isGreaterThan(c.version());
        assertThat(history(c.id())).contains("COMPLIANT>APPROVED:APPROVE:ana");
    }

    @Test
    void approvingADivergentCaseWithAGlossa() throws Exception {
        StoredCase c = divergent();
        StoredCase approved = asAuditor(() -> workflow.approve(c.id(), c.version(), new BigDecimal("66.02"),
                "Glosa conforme tabela", "ana"));
        assertThat(approved.status().name()).isEqualTo("APPROVED");
        assertThat(approved.approvedAmount()).isEqualByComparingTo("66.02");
        assertThat(admin.queryForObject("select note from audit.case_transition where case_id = ? and action = 'APPROVE'",
                String.class, c.id())).isEqualTo("Glosa conforme tabela");
        var event = events("audit.status-changed").stream().filter(e -> "APPROVED".equals(e.getData().get("toStatus")))
                .findFirst().orElseThrow();
        assertThat(event.getData()).containsEntry("fromStatus", "DIVERGENT").containsEntry("actor", "ana");
        assertThat(String.valueOf(event.getData().get("approvedAmount"))).startsWith("66.02");
    }

    @Test
    void aDivergentCaseApprovedWithoutJustificationIs422AndDoesNotChange() throws Exception {
        StoredCase c = divergent();
        AuditException e = failure(() -> workflow.approve(c.id(), c.version(), null, " ", "ana"));
        assertThat(e.kind()).isEqualTo(AuditException.Kind.INVALID);
        assertThat(e.issues()).extracting(AuditException.FieldIssue::field).containsExactly("justification");
        assertThat(reload(c.id()).status().name()).isEqualTo("DIVERGENT");
    }

    @Test
    void aCompliantCaseApprovedWithADifferentAmountNeedsJustification() throws Exception {
        StoredCase c = compliant();
        assertThat(failure(() -> workflow.approve(c.id(), c.version(), new BigDecimal("60.00"), null, "ana")).kind())
                .isEqualTo(AuditException.Kind.INVALID);
    }

    @Test
    void anApprovedAmountAboveTheChargedOrNegativeIs422() throws Exception {
        StoredCase c = divergent();
        assertThat(failure(() -> workflow.approve(c.id(), c.version(), new BigDecimal("80.01"), "x", "ana")).issues())
                .extracting(AuditException.FieldIssue::field).containsExactly("approvedAmount");
        assertThat(failure(() -> workflow.approve(c.id(), c.version(), new BigDecimal("-1"), "x", "ana")).kind())
                .isEqualTo(AuditException.Kind.INVALID);
        assertThat(failure(() -> workflow.approve(c.id(), c.version(), new BigDecimal("70.123"), "x", "ana")).kind())
                .isEqualTo(AuditException.Kind.INVALID);
    }

    @Test
    void anInvalidActionForTheStateIs409WithTheCurrentStatus() throws Exception {
        StoredCase c = compliant();
        asAuditor(() -> workflow.approve(c.id(), c.version(), null, null, "ana"));
        StoredCase approved = reload(c.id());
        // contestar um APPROVED não é permitido
        AuditException e = failure(() -> workflow.dispute(c.id(), approved.version(), DisputeCategory.OTHER, "x",
                BigDecimal.ZERO, "ana"));
        assertThat(e.kind()).isEqualTo(AuditException.Kind.CONFLICT);
        assertThat(e.code()).isEqualTo("INVALID_TRANSITION");
        assertThat(e.currentStatus()).isEqualTo("APPROVED");
    }

    @Test
    void aPaidCaseCannotBeDisputed() throws Exception {
        StoredCase c = compliant();
        asAuditor(() -> workflow.approve(c.id(), c.version(), null, null, "ana"));
        as(() -> {
            commands.schedule(List.of(c.id()), UUID.randomUUID(), "ana");
            commands.markPaid(List.of(c.id()), "ana");
            return null;
        });
        StoredCase paid = reload(c.id());
        AuditException e = failure(() -> workflow.dispute(c.id(), paid.version(), DisputeCategory.OTHER, "x",
                BigDecimal.ZERO, "ana"));
        assertThat(e.currentStatus()).isEqualTo("PAID");
        assertThat(reload(c.id()).status().name()).isEqualTo("PAID");
    }

    @Test
    void disputeThenResolveApprovesWithTheAgreedAmount() throws Exception {
        StoredCase c = divergent();
        asAuditor(() -> workflow.dispute(c.id(), c.version(), DisputeCategory.DUPLICATE_CHARGE,
                "NF-e já faturada no CT-e 1234", BigDecimal.ZERO, "ana"));
        StoredCase disputed = reload(c.id());
        assertThat(disputed.status().name()).isEqualTo("DISPUTED");
        var dispute = admin.queryForMap("select category, description, amount_due, status from audit.dispute where case_id = ?",
                c.id());
        assertThat(dispute).containsEntry("category", "DUPLICATE_CHARGE").containsEntry("status", "OPEN")
                .containsEntry("description", "NF-e já faturada no CT-e 1234");
        assertThat((BigDecimal) dispute.get("amount_due")).isEqualByComparingTo("0.00");

        StoredCase resolved = asAuditor(() -> workflow.resolve(c.id(), disputed.version(), new BigDecimal("66.02"),
                "Transportadora concedeu desconto na fatura", "ana"));
        assertThat(resolved.status().name()).isEqualTo("APPROVED");
        assertThat(resolved.approvedAmount()).isEqualByComparingTo("66.02");
        assertThat(admin.queryForObject("select status from audit.dispute where case_id = ?", String.class, c.id()))
                .isEqualTo("RESOLVED");
        assertThat(history(c.id())).containsExactly("PENDING_AUDIT>DIVERGENT:AUDIT:SYSTEM",
                "DIVERGENT>DISPUTED:DISPUTE:ana", "DISPUTED>APPROVED:RESOLVE:ana");
    }

    @Test
    void resolveValidatesAmountAndNote() throws Exception {
        StoredCase c = divergent();
        asAuditor(() -> workflow.dispute(c.id(), c.version(), DisputeCategory.OTHER, "x", BigDecimal.ZERO, "ana"));
        StoredCase d = reload(c.id());
        assertThat(failure(() -> workflow.resolve(c.id(), d.version(), new BigDecimal("81"), "n", "ana")).kind())
                .isEqualTo(AuditException.Kind.INVALID);
        assertThat(failure(() -> workflow.resolve(c.id(), d.version(), new BigDecimal("70"), "", "ana")).kind())
                .isEqualTo(AuditException.Kind.INVALID);
        assertThat(reload(c.id()).status().name()).isEqualTo("DISPUTED");
    }

    @Test
    void disputeValidatesItsFields() throws Exception {
        StoredCase c = divergent();
        assertThat(failure(() -> workflow.dispute(c.id(), c.version(), null, "", new BigDecimal("-1"), "ana")).issues())
                .extracting(AuditException.FieldIssue::field).containsExactlyInAnyOrder("category", "description", "amountDue");
    }

    @Test
    void withdrawingTheDisputeReturnsToTheLastClassification() throws Exception {
        StoredCase c = divergent();
        asAuditor(() -> workflow.dispute(c.id(), c.version(), DisputeCategory.OTHER, "x", BigDecimal.ZERO, "ana"));
        StoredCase d = reload(c.id());
        StoredCase back = asAuditor(() -> workflow.withdraw(c.id(), d.version(), "Desisti", "ana"));
        assertThat(back.status().name()).isEqualTo("DIVERGENT");
        assertThat(admin.queryForObject("select status from audit.dispute where case_id = ?", String.class, c.id()))
                .isEqualTo("WITHDRAWN");
    }

    @Test
    void reopeningAnAutoApprovedCaseReturnsToCompliantWithoutApprovedAmount() throws Exception {
        autoApprove(true);
        StoredCase c = compliant();
        StoredCase reopened = asAuditor(() -> workflow.reopen(c.id(), c.version(), "Revisar peso", "ana"));
        assertThat(reopened.status().name()).isEqualTo("COMPLIANT");
        assertThat(reopened.approvedAmount()).isNull();
        assertThat(history(c.id())).last().isEqualTo("APPROVED>COMPLIANT:REOPEN:ana");
        assertThat(failure(() -> workflow.reopen(c.id(), reopened.version(), "x", "ana")).currentStatus())
                .isEqualTo("COMPLIANT");
    }

    @Test
    void aCaseInABatchCannotBeReopened() throws Exception {
        autoApprove(true);
        StoredCase c = compliant();
        as(() -> {
            commands.schedule(List.of(c.id()), UUID.randomUUID(), "ana");
            return null;
        });
        StoredCase scheduled = reload(c.id());
        AuditException e = failure(() -> workflow.reopen(c.id(), scheduled.version(), "x", "ana"));
        assertThat(e.kind()).isEqualTo(AuditException.Kind.CONFLICT);
        assertThat(e.currentStatus()).isEqualTo("SCHEDULED_FOR_PAYMENT");
    }

    @Test
    void reopenNeedsAReason() throws Exception {
        autoApprove(true);
        StoredCase c = compliant();
        assertThat(failure(() -> workflow.reopen(c.id(), c.version(), " ", "ana")).kind())
                .isEqualTo(AuditException.Kind.INVALID);
    }

    @Test
    void aStaleVersionIs409AndNothingChanges() throws Exception {
        StoredCase c = divergent();
        AuditException e = failure(() -> workflow.approve(c.id(), c.version() - 1, null, "x", "ana"));
        assertThat(e.kind()).isEqualTo(AuditException.Kind.CONFLICT);
        assertThat(e.code()).isEqualTo("VERSION_CONFLICT");
        assertThat(reload(c.id()).status().name()).isEqualTo("DIVERGENT");
    }

    @Test
    void anUnknownCaseIs404() {
        assertThat(failure(() -> workflow.approve(UUID.randomUUID(), 1, null, "x", "ana")).kind())
                .isEqualTo(AuditException.Kind.NOT_FOUND);
    }

    @Test
    void twoAuditorsActingOnTheSameVersionOnlyTheFirstIsApplied() throws Exception {
        StoredCase c = divergent();
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (String who : List.of("ana", "bia")) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    try {
                        as(() -> workflow.approve(c.id(), c.version(), new BigDecimal("66.02"), "glosa de " + who, who));
                        return "OK";
                    } catch (AuditException e) {
                        return e.code();
                    }
                }));
            }
            List<String> outcomes = new ArrayList<>();
            for (var f : futures) {
                outcomes.add(f.get());
            }
            assertThat(outcomes).containsExactlyInAnyOrder("OK", "VERSION_CONFLICT");
        } finally {
            pool.shutdownNow();
        }
        assertThat(admin.queryForObject("select count(*) from audit.case_transition where case_id = ? and action = 'APPROVE'",
                Integer.class, c.id())).isEqualTo(1);
    }

    // ---- 12.5 lote

    @Test
    void batchApprovalReportsEachCaseAndAFailureDoesNotStopTheOthers() throws Exception {
        List<StoredCase> compliant = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            compliant.add(compliant());
        }
        StoredCase paid = compliant();
        asAuditor(() -> workflow.approve(paid.id(), paid.version(), null, null, "ana"));
        as(() -> {
            commands.schedule(List.of(paid.id()), UUID.randomUUID(), "ana");
            commands.markPaid(List.of(paid.id()), "ana");
            return null;
        });
        StoredCase paidNow = reload(paid.id());
        List<BatchItem> items = new ArrayList<>(compliant.stream().map(c -> new BatchItem(c.id(), c.version())).toList());
        items.add(new BatchItem(paid.id(), paidNow.version()));

        var results = as(() -> workflow.approveBatch(items, "Lote de segunda", "ana"));

        assertThat(results).hasSize(4);
        assertThat(results.subList(0, 3)).allMatch(CaseWorkflow.BatchResult::success)
                .allMatch(r -> r.status().name().equals("APPROVED"));
        assertThat(results.get(3).success()).isFalse();
        assertThat(results.get(3).errorCode()).isEqualTo("INVALID_TRANSITION");
        for (StoredCase c : compliant) {
            assertThat(reload(c.id()).status().name()).isEqualTo("APPROVED");
        }
        assertThat(reload(paid.id()).status().name()).isEqualTo("PAID");
    }

    @Test
    void aBatchOfMoreThan500CasesIsRejected() {
        List<BatchItem> items = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            items.add(new BatchItem(UUID.randomUUID(), 1));
        }
        assertThat(failure(() -> workflow.approveBatch(items, null, "ana")).kind()).isEqualTo(AuditException.Kind.INVALID);
        assertThat(failure(() -> workflow.approveBatch(List.of(), null, "ana")).kind()).isEqualTo(AuditException.Kind.INVALID);
    }

    @Test
    void aBatchOf500IsAccepted() throws Exception {
        // 500 itens inexistentes: o limite é aceito e cada um volta com NOT_FOUND
        List<BatchItem> items = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            items.add(new BatchItem(UUID.randomUUID(), 1));
        }
        var results = as(() -> workflow.approveBatch(items, null, "ana"));
        assertThat(results).hasSize(500).allMatch(r -> !r.success() && "NOT_FOUND".equals(r.errorCode()));
    }

    // ---- 12.6 cancelamento

    @Test
    void cancellingTheCteOfAnApprovedCaseCancelsIt() throws Exception {
        autoApprove(true);
        UUID doc = cteDocument("66.02");
        StoredCase c = audit(doc).orElseThrow();
        assertThat(c.status().name()).isEqualTo("APPROVED");

        StoredCase cancelled = as(() -> cancellation.cancelByCte(doc, CaseCancellation.CTE_CANCELLED).orElseThrow());

        assertThat(cancelled.status().name()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelReason()).isEqualTo("CTE_CANCELLED");
        assertThat(history(c.id())).last().isEqualTo("APPROVED>CANCELLED:CANCEL:SYSTEM");
        // repetir não muda nada
        assertThat(as(() -> cancellation.cancelByCte(doc, CaseCancellation.CTE_CANCELLED).orElseThrow()).version())
                .isEqualTo(cancelled.version());
    }

    @Test
    void aSubstitutedCteCancelsTheCaseWithTheSubstitutionReason() throws Exception {
        UUID doc = cteDocument("66.02");
        audit(doc).orElseThrow();
        StoredCase cancelled = as(() -> cancellation.cancelByCte(doc, CaseCancellation.CTE_SUBSTITUTED).orElseThrow());
        assertThat(cancelled.cancelReason()).isEqualTo("CTE_SUBSTITUTED");
    }

    @Test
    void cancellingAPaidCaseKeepsItPaidWithAnAlert() throws Exception {
        UUID doc = cteDocument("66.02");
        StoredCase c = audit(doc).orElseThrow();
        asAuditor(() -> workflow.approve(c.id(), c.version(), null, null, "ana"));
        as(() -> {
            commands.schedule(List.of(c.id()), UUID.randomUUID(), "ana");
            commands.markPaid(List.of(c.id()), "ana");
            return null;
        });

        StoredCase result = as(() -> cancellation.cancelByCte(doc, CaseCancellation.CTE_CANCELLED).orElseThrow());

        assertThat(result.status().name()).isEqualTo("PAID");
        assertThat(result.alerts()).contains("CANCELLED_AFTER_PAYMENT");
        // duas vezes não duplica o alerta
        assertThat(as(() -> cancellation.cancelByCte(doc, CaseCancellation.CTE_CANCELLED).orElseThrow()).alerts()
                .stream().filter("CANCELLED_AFTER_PAYMENT"::equals)).hasSize(1);
    }

    @Test
    void aCaseCreatedAfterTheCteWasCancelledIsBornCancelled() throws Exception {
        UUID doc = cteDocument("66.02");
        admin.update("update documents.document set lifecycle = 'CANCELLED' where id = ?", doc);
        StoredCase c = audit(doc).orElseThrow();
        assertThat(c.status().name()).isEqualTo("CANCELLED");
        assertThat(c.cancelReason()).isEqualTo("CTE_CANCELLED");
    }

    @Test
    void cancelWithoutACaseYetReturnsEmpty() throws Exception {
        assertThat(as(() -> cancellation.cancelByCte(UUID.randomUUID(), CaseCancellation.CTE_CANCELLED))).isEmpty();
    }

    // ---- 12.7 comandos da liquidação

    @Test
    void scheduleUnscheduleAndPayGuardTheState() throws Exception {
        autoApprove(true);
        StoredCase a = compliant();
        StoredCase b = compliant();
        UUID batch = UUID.randomUUID();

        as(() -> {
            commands.schedule(List.of(a.id(), b.id()), batch, "ana");
            return null;
        });
        assertThat(reload(a.id()).status().name()).isEqualTo("SCHEDULED_FOR_PAYMENT");
        assertThat(admin.queryForObject("select batch_id from audit.audit_case where id = ?", UUID.class, a.id())).isEqualTo(batch);

        as(() -> {
            commands.unschedule(List.of(a.id()), "ana");
            return null;
        });
        assertThat(reload(a.id()).status().name()).isEqualTo("APPROVED");
        assertThat(admin.queryForObject("select batch_id from audit.audit_case where id = ?", UUID.class, a.id())).isNull();

        as(() -> {
            commands.markPaid(List.of(b.id()), "ana");
            return null;
        });
        assertThat(reload(b.id()).status().name()).isEqualTo("PAID");
        // pagar de novo falha: já está PAID
        assertThat(thrown(() -> commands.markPaid(List.of(b.id()), "ana")).rejected())
                .extracting(AuditCaseCommands.Rejected::currentStatus).containsExactly("PAID");
    }

    @Test
    void aCancelledCaseFailsTheWholeOperationAndNothingChanges() throws Exception {
        autoApprove(true);
        UUID docCancelled = cteDocument("66.02");
        StoredCase cancelled = audit(docCancelled).orElseThrow();
        as(() -> cancellation.cancelByCte(docCancelled, CaseCancellation.CTE_CANCELLED));
        StoredCase ok = compliant();

        CaseCommandException e = thrown(() -> commands.schedule(List.of(ok.id(), cancelled.id()), UUID.randomUUID(), "ana"));

        assertThat(e.hasCancelled()).isTrue();
        assertThat(e.rejected()).extracting(AuditCaseCommands.Rejected::caseId).containsExactly(cancelled.id());
        assertThat(reload(ok.id()).status().name()).isEqualTo("APPROVED");
    }

    @Test
    void schedulingAnUnapprovedOrUnknownCaseFails() throws Exception {
        StoredCase c = divergent();
        CaseCommandException e = thrown(() -> commands.schedule(List.of(c.id(), UUID.randomUUID()), UUID.randomUUID(), "ana"));
        assertThat(e.rejected()).hasSize(2);
        assertThat(e.rejected()).extracting(AuditCaseCommands.Rejected::currentStatus).containsExactlyInAnyOrder("DIVERGENT", null);
    }

    private CaseCommandException thrown(Runnable call) throws Exception {
        try {
            as(() -> {
                call.run();
                return null;
            });
        } catch (CaseCommandException e) {
            return e;
        }
        throw new AssertionError("era esperada uma CaseCommandException");
    }
}

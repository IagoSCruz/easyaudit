package br.com.easyaudit.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.Test;

class AuditFlowIT extends AuditFlowSupport {

    @Test
    void aCteIsAuditedRightAfterTheCaseIsCreated() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(1).volume(1, "10.000", "12.500").build();
        process(nfe);
        UUID cteDoc = process(cte().nfeKey(nfe.accessKey()).build());

        StoredCase created = audit(cteDoc).orElseThrow();

        assertThat(created.status().name()).isEqualTo("COMPLIANT");
        assertThat(created.currentRevision()).isEqualTo(1);
        assertThat(created.chargedAmount()).isEqualByComparingTo("66.02");
        assertThat(created.referenceAmount()).isEqualByComparingTo("66.02");
        assertThat(created.divergenceAmount()).isEqualByComparingTo("0.00");
        assertThat(created.carrierId()).isEqualTo(carrierId);
        assertThat(created.carrierName()).isEqualTo("Rápidos");
        assertThat(created.destinationUf()).isEqualTo("SP");
        assertThat(created.emissionDate().toString()).isEqualTo("2026-10-10");
        assertThat(created.failedChecks()).isEmpty();
        assertThat(created.version()).isGreaterThan(0);
    }

    @Test
    void theRevisionKeepsAnImmutableSnapshotWithPolicyTableAndComparison() throws Exception {
        UUID cteDoc = process(cte().build());
        StoredCase c = audit(cteDoc).orElseThrow();

        var row = admin.queryForMap("select trigger, classification, snapshot::text as snapshot from audit.audit_revision where case_id = ?", c.id());
        assertThat(row.get("trigger")).isEqualTo("INITIAL");
        assertThat(row.get("classification")).isEqualTo("COMPLIANT");
        String snapshot = (String) row.get("snapshot");
        assertThat(snapshot).contains("\"policy\"").contains("absoluteTolerance").contains("tableVersion")
                .contains("componentComparison").contains("TOTAL_VS_TABLE");
    }

    @Test
    void auditCompletedAndStatusChangedAreEmitted() throws Exception {
        UUID cteDoc = process(cte().build());
        StoredCase c = audit(cteDoc).orElseThrow();

        assertThat(events("audit.completed")).hasSize(1);
        var data = events("audit.completed").get(0).getData();
        assertThat(data).containsEntry("caseId", c.id().toString()).containsEntry("classification", "COMPLIANT");
        assertThat(events("audit.status-changed")).hasSize(1);
        assertThat(events("audit.status-changed").get(0).getData())
                .containsEntry("fromStatus", "PENDING_AUDIT").containsEntry("toStatus", "COMPLIANT").containsEntry("actor", "SYSTEM");
        var transition = admin.queryForMap("select from_status, to_status, actor, action from audit.case_transition where case_id = ?", c.id());
        assertThat(transition).containsEntry("from_status", "PENDING_AUDIT").containsEntry("to_status", "COMPLIANT")
                .containsEntry("actor", "SYSTEM").containsEntry("action", "AUDIT");
    }

    @Test
    void aRepeatedNotificationCreatesASingleCase() throws Exception {
        UUID cteDoc = process(cte().build());
        assertThat(audit(cteDoc)).isPresent();
        assertThat(audit(cteDoc)).isEmpty();
        assertThat(admin.queryForObject("select count(*) from audit.audit_case", Integer.class)).isEqualTo(1);
        assertThat(admin.queryForObject("select count(*) from audit.audit_revision", Integer.class)).isEqualTo(1);
    }

    @Test
    void aDivergentChargeIsClassifiedAsDivergent() throws Exception {
        UUID cteDoc = process(cte().totalAmount("80.00").build());
        StoredCase c = audit(cteDoc).orElseThrow();
        assertThat(c.status().name()).isEqualTo("DIVERGENT");
        assertThat(c.failedChecks()).containsExactly("TOTAL_VS_TABLE");
        assertThat(c.divergenceAmount()).isEqualByComparingTo("13.98");
    }

    @Test
    void withoutATableTheCaseIsNotAuditable() throws Exception {
        admin.execute("update freight.freight_table set status = 'ARCHIVED'");
        UUID cteDoc = process(cte().build());
        StoredCase c = audit(cteDoc).orElseThrow();
        assertThat(c.status().name()).isEqualTo("NOT_AUDITABLE");
        assertThat(c.referenceAmount()).isNull();
        String snapshot = admin.queryForObject("select snapshot::text from audit.audit_revision where case_id = ?", String.class, c.id());
        assertThat(snapshot).contains("NO_TABLE");
    }

    @Test
    void theWeightFallsBackToTheSumOfTheNfeGrossWeights() throws Exception {
        // CT-e sem peso real e sem volume: usa 5.000 kg das NF-es (3.000 + 2.000); o frete excedente aparece no cálculo
        FiscalXml nfe1 = NfeFixture.builder().number(11).volume(1, "2800.000", "3000.000").build();
        FiscalXml nfe2 = NfeFixture.builder().number(12).volume(1, "1800.000", "2000.000").build();
        process(nfe1);
        process(nfe2);
        UUID cteDoc = process(CteFixture.builder().destination("3550308", "Sao Paulo", "SP", "01310100")
                .withoutQuantities().cargoValue("1000.00").totalAmount("100.00").nfeKey(nfe1.accessKey()).nfeKey(nfe2.accessKey()).build());

        StoredCase c = audit(cteDoc).orElseThrow();

        // 5.000 kg: frete peso 45.00 + 4.970 kg × 1.20 = 6.009,00, mais ad valorem, GRIS, pedágio e despacho
        assertThat(c.referenceAmount()).isGreaterThan(new BigDecimal("6000"));
        String snapshot = admin.queryForObject("select snapshot::text from audit.audit_revision where case_id = ?", String.class, c.id());
        assertThat(snapshot).contains("5000");
        assertThat(c.status().name()).isEqualTo("COMPLIANT"); // cobrar menos que a referência é conforme (UNDERCHARGE)
    }

    @Test
    void twoConcurrentCtesOfTheSameNfeGetExactlyOneDuplicateFailure() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(21).volume(1, "10.000", "12.500").build();
        process(nfe);
        UUID doc1 = process(cte().number(101).nfeKey(nfe.accessKey()).build());
        UUID doc2 = process(cte().number(102).nfeKey(nfe.accessKey()).build());

        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Callable<Object>> tasks = List.of(
                    () -> { barrier.await(); return audit(doc1); },
                    () -> { barrier.await(); return audit(doc2); });
            for (var f : pool.invokeAll(tasks)) {
                f.get();
            }
        }

        List<List<String>> failures = admin.queryForList("select failed_checks::text from audit.audit_case order by created_at", String.class)
                .stream().map(s -> List.of(s.replace("{", "").replace("}", "").split(","))).toList();
        long duplicates = admin.queryForObject("select count(*) from audit.audit_case where 'DUPLICATE_NFE' = any(failed_checks)", Long.class);
        assertThat(duplicates).as("falhas por caso: %s", failures).isEqualTo(1L);
        assertThat(admin.queryForObject("select count(*) from audit.audit_case", Integer.class)).isEqualTo(2);
    }

    @Test
    void theLaterCteIsTheDuplicateAndPointsToTheFirst() throws Exception {
        FiscalXml nfe = NfeFixture.builder().number(22).volume(1, "10.000", "12.500").build();
        process(nfe);
        UUID doc1 = process(cte().number(201).nfeKey(nfe.accessKey()).build());
        UUID doc2 = process(cte().number(202).nfeKey(nfe.accessKey()).build());
        StoredCase first = audit(doc1).orElseThrow();
        StoredCase second = audit(doc2).orElseThrow();

        assertThat(first.failedChecks()).isEmpty();
        assertThat(second.failedChecks()).contains("DUPLICATE_NFE");
        assertThat(second.status().name()).isEqualTo("DIVERGENT");
        String snapshot = admin.queryForObject("select snapshot::text from audit.audit_revision where case_id = ?", String.class, second.id());
        assertThat(snapshot).contains(first.cteAccessKey());
    }

    @Test
    void casesOfOtherTenantsAreInvisible() throws Exception {
        UUID cteDoc = process(cte().build());
        StoredCase c = audit(cteDoc).orElseThrow();
        Integer seenByB = TenantContext.callAs(TwoTenants.B, () -> tx.execute(s -> applicationEventsNone(c)));
        assertThat(seenByB).isZero();
    }

    private Integer applicationEventsNone(StoredCase c) {
        return admin.getDataSource() == null ? 0 : new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(environment.getRequiredProperty("spring.datasource.url"), "easyaudit_app", "easyaudit_app"))
                .queryForObject("select count(*) from audit.audit_case", Integer.class);
    }
}

package br.com.easyaudit.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.application.Reaudits;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReauditIT extends AuditFlowSupport {

    @Autowired
    Reaudits reaudits;

    private int revisions(UUID caseId) {
        return admin.queryForObject("select count(*) from audit.audit_revision where case_id = ?", Integer.class, caseId);
    }

    @Test
    void aLateNfeMakesTheCaseAuditable() throws Exception {
        var nfe = NfeFixture.builder().number(31).volume(1, "2800.000", "3000.000").build();
        // CT-e sem peso próprio: precisa da NF-e para auditar
        UUID cteDoc = process(CteFixture.builder().destination("3550308", "Sao Paulo", "SP", "01310100")
                .withoutQuantities().cargoValue("1000.00").totalAmount("100.00").nfeKey(nfe.accessKey()).build());
        var first = audit(cteDoc).orElseThrow();
        assertThat(first.failedChecks()).isNotEmpty();
        var before = first.failedChecks();

        process(nfe);
        TenantContext.callAs(TwoTenants.A, () -> {
            reaudits.onLinkResolved(cteDoc);
            return null;
        });

        assertThat(admin.queryForList("select unnest(failed_checks) from audit.audit_case where id = ?", String.class,
                first.id())).isNotEqualTo(before);
        assertThat(revisions(first.id())).isEqualTo(2);
        assertThat(admin.queryForObject("select trigger from audit.audit_revision where case_id = ? and revision = 2",
                String.class, first.id())).isEqualTo("LINK_RESOLVED");
    }

    @Test
    void anActivatedTableReauditsTheCarrierCasesInTheValidityRange() throws Exception {
        List<UUID> caseIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            UUID doc = process(cte().number(500 + i).build());
            caseIds.add(audit(doc).orElseThrow().id());
        }
        OffsetDateTime eventTime = OffsetDateTime.now();
        as(() -> activateTableT(LocalDate.of(2026, 10, 1)));

        int total = TenantContext.callAs(TwoTenants.A,
                () -> reaudits.onTableActivated(carrierId, LocalDate.of(2026, 10, 1), null, eventTime));

        assertThat(total).isEqualTo(10);
        for (UUID id : caseIds) {
            assertThat(revisions(id)).isEqualTo(2);
        }
        // reentrega do mesmo evento: os casos já têm revisão posterior ao evento? não — a reentrega refaz só os que
        // ainda estão antes do instante do evento; aqui todos estão depois, então nada se repete
        int again = TenantContext.callAs(TwoTenants.A,
                () -> reaudits.onTableActivated(carrierId, LocalDate.of(2026, 10, 1), null, eventTime));
        assertThat(again).isZero();
    }

    @Test
    void casesOutsideTheValidityRangeAreNotReaudited() throws Exception {
        UUID doc = process(cte().build()); // emitido em 2026-10-10
        UUID caseId = audit(doc).orElseThrow().id();
        OffsetDateTime eventTime = OffsetDateTime.now();

        int total = TenantContext.callAs(TwoTenants.A, () -> reaudits.onTableActivated(carrierId,
                LocalDate.of(2026, 11, 1), null, eventTime));

        assertThat(total).isZero();
        assertThat(revisions(caseId)).isEqualTo(1);
    }

    @Test
    void anApprovedCaseIsNotReaudited() throws Exception {
        UUID doc = process(cte().build());
        UUID caseId = audit(doc).orElseThrow().id();
        admin.update("update audit.audit_case set status = 'APPROVED' where id = ?", caseId);

        TenantContext.callAs(TwoTenants.A, () -> {
            reaudits.onLinkResolved(doc);
            return null;
        });
        int total = TenantContext.callAs(TwoTenants.A, () -> reaudits.onTableActivated(carrierId,
                LocalDate.of(2026, 1, 1), null, OffsetDateTime.now()));

        assertThat(total).isZero();
        assertThat(revisions(caseId)).isEqualTo(1);
        assertThat(statusOf(caseId)).isEqualTo("APPROVED");
    }
}

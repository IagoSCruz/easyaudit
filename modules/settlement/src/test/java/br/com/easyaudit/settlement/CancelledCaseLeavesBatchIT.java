package br.com.easyaudit.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.application.CaseCancellation;
import br.com.easyaudit.platform.messaging.OutboxConfiguration;
import br.com.easyaudit.platform.testsupport.PostgresContainerSupport;
import br.com.easyaudit.platform.testsupport.RabbitContainerSupport;
import br.com.easyaudit.settlement.application.BatchModel.Detail;
import br.com.easyaudit.settlement.application.PaymentBatches;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Pelas filas: o cancelamento do CT-e de um caso em lote aberto o tira do lote e recalcula os totais. */
@SpringBootTest(classes = {SettlementTestApplication.class, OutboxConfiguration.class}, properties = {
        "spring.jpa.hibernate.ddl-auto=none", "easyaudit.messaging.listeners.enabled=true",
        "easyaudit.messaging.retry.initial-interval=50ms", "easyaudit.messaging.retry.multiplier=2",
        "easyaudit.messaging.retry.max-interval=500ms"})
class CancelledCaseLeavesBatchIT extends AbstractSettlementIT {

    @DynamicPropertySource
    static void rabbit(DynamicPropertyRegistry registry) {
        RabbitContainerSupport.register(registry);
    }

    @Autowired
    PaymentBatches batches;
    @Autowired
    CaseCancellation cancellation;

    @Test
    void aCancelledCteTakesTheCaseOutOfTheOpenBatch() throws Exception {
        UUID a = approved(CARRIER_X, "Rápidos", "103.00", "100.00");
        UUID b = approved(CARRIER_X, "Rápidos", "66.02", "66.02");
        Detail created = asA(() -> batches.create(LocalDate.of(2026, 11, 10), null, List.of(a, b), "bia"));
        UUID cteDocument = admin.queryForObject("select cte_document_id from audit.audit_case where id = ?", UUID.class, b);

        asA(() -> cancellation.cancelByCte(cteDocument, CaseCancellation.CTE_CANCELLED));

        assertThat(statusOf(b)).isEqualTo("CANCELLED");
        await().atMost(Duration.ofSeconds(40)).until(() ->
                asA(() -> batches.get(created.batch().id())).batch().caseCount() == 1);
        Detail after = asA(() -> batches.get(created.batch().id()));
        assertThat(after.batch().chargedTotal()).isEqualByComparingTo("103.00");
        assertThat(after.batch().approvedTotal()).isEqualByComparingTo("100.00");
        assertThat(after.items()).hasSize(1);
        assertThat(admin.queryForObject("select release_reason from settlement.batch_item where case_id = ?", String.class, b))
                .isEqualTo("CASE_CANCELLED");
    }
}

package br.com.easyaudit.audit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import br.com.easyaudit.audit.application.AuditCases;
import br.com.easyaudit.audit.application.PolicyService;
import br.com.easyaudit.audit.domain.AuditPolicy;
import br.com.easyaudit.audit.domain.StoredCase;
import br.com.easyaudit.contracts.api.model.FreightTableContent;
import br.com.easyaudit.contracts.events.EventEnvelope;
import br.com.easyaudit.documents.application.IngestDocument;
import br.com.easyaudit.documents.application.NormalizeDocument;
import br.com.easyaudit.documents.domain.Source;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.freight.fixtures.FreightTableFixtures;
import br.com.easyaudit.freight.application.CarrierService;
import br.com.easyaudit.freight.application.FreightTableService;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.shared.TenantId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cenário comum dos testes de fluxo da auditoria: a transportadora X (11.222.333/0001-81) com a tabela de referência T
 * ativa desde 2026-01-01, o tenant A (dono da raiz 45723174, a da loja) e ajudantes para receber documentos.
 */
abstract class AuditFlowSupport extends AbstractAuditIT {

    static final String CARRIER_CNPJ = "11222333000181";

    @Autowired
    IngestDocument ingest;
    @Autowired
    NormalizeDocument normalize;
    @Autowired
    CarrierService carriers;
    @Autowired
    FreightTableService freightTables;
    @Autowired
    PolicyService policies;
    @Autowired
    AuditCases auditCases;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    ApplicationEvents applicationEvents;

    UUID carrierId;
    UUID tableId;

    @org.junit.jupiter.api.BeforeEach
    void flowSetUp() throws Exception {
        carrierId = as(() -> carriers.create(CARRIER_CNPJ, "Rápidos").id());
        tableId = as(() -> activateTableT(LocalDate.of(2026, 1, 1)));
        // por padrão sem aprovação automática, para observar a classificação; os testes de workflow ligam
        as(() -> policies.updateTenantPolicy(new AuditPolicy(new BigDecimal("0.00"), BigDecimal.ZERO, false,
                new BigDecimal("5"), new BigDecimal("1"))));
    }

    <T> T as(java.util.concurrent.Callable<T> call) throws Exception {
        return TenantContext.callAs(TwoTenants.A, call);
    }

    UUID activateTableT(LocalDate validFrom) {
        FreightTableContent content = FreightTableFixtures.tableT();
        content.setValidFrom(validFrom);
        UUID id = freightTables.create(carrierId, content, FreightTableService.JSON_LOCATION).id();
        freightTables.activate(id);
        return id;
    }

    /** CT-e da transportadora X para SP, 12,5 kg reais e 0,05 m³ (15 kg taxados), carga de 1000.00 e ICMS de 12%. */
    CteFixture cte() {
        return CteFixture.builder().destination("3550308", "Sao Paulo", "SP", "01310100")
                .quantity("01", "PESO BRUTO", "12.5").quantity("00", "VOLUME", "0.05").cargoValue("1000.00")
                .icms("00", "66.02", "12.00", "7.92").totalAmount("66.02")
                .component("FREIGHT_WEIGHT", "45.00").component("AD_VALOREM", "3.00").component("GRIS", "2.00")
                .component("TOLL", "3.10").component("DISPATCH_FEE", "5.00").component("ICMS", "7.92");
    }

    /** Recebe e normaliza o documento no tenant. @return o id do documento */
    UUID process(TenantId tenant, FiscalXml xml) throws Exception {
        UUID id = TenantContext.callAs(tenant, () -> ingest.ingest(Source.UPLOAD, "x.xml", xml.bytes()).documentId());
        TenantContext.callAs(tenant, () -> tx.execute(s -> {
            normalize.normalize(id);
            return null;
        }));
        return id;
    }

    UUID process(FiscalXml xml) throws Exception {
        return process(TwoTenants.A, xml);
    }

    java.util.Optional<StoredCase> audit(UUID cteDocumentId) throws Exception {
        return as(() -> auditCases.createAndAudit(cteDocumentId));
    }

    List<EventEnvelope> events(String type) {
        return applicationEvents.stream(EventEnvelope.class)
                .filter(e -> type.equals(e.getEventType().getValue())).toList();
    }

    String statusOf(UUID caseId) {
        return admin.queryForObject("select status from audit.audit_case where id = ?", String.class, caseId);
    }
}

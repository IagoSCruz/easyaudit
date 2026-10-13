package br.com.easyaudit.audit.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import br.com.easyaudit.audit.domain.AuditInput;
import br.com.easyaudit.audit.domain.AuditInput.Calculated;
import br.com.easyaudit.audit.domain.AuditInput.ChargedComponent;
import br.com.easyaudit.audit.domain.AuditInput.CteType;
import br.com.easyaudit.audit.domain.AuditInput.EarlierCte;
import br.com.easyaudit.audit.domain.AuditInput.Freight;
import br.com.easyaudit.audit.domain.AuditInput.FreightMode;
import br.com.easyaudit.audit.domain.AuditInput.Nfe;
import br.com.easyaudit.audit.domain.AuditInput.NoTable;
import br.com.easyaudit.audit.domain.AuditInput.Quote;
import br.com.easyaudit.audit.domain.AuditInput.RouteNotCovered;
import br.com.easyaudit.audit.domain.AuditPolicy;
import br.com.easyaudit.audit.infrastructure.persistence.CaseRepository;
import br.com.easyaudit.contracts.api.model.CanonicalCte;
import br.com.easyaudit.contracts.api.model.CanonicalNfe;
import br.com.easyaudit.documents.api.DocumentCatalog;
import br.com.easyaudit.documents.api.DocumentCatalog.CanonicalDocument;
import br.com.easyaudit.documents.api.DocumentCatalog.Link;
import br.com.easyaudit.freight.api.CarrierDirectory;
import br.com.easyaudit.freight.api.CarrierDirectory.CarrierRef;
import br.com.easyaudit.freight.api.FreightCalculation;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.quotes.api.QuoteLookup;
import br.com.easyaudit.shared.Cnpj;
import br.com.easyaudit.tenancy.api.TenantDirectory;
import org.springframework.stereotype.Component;

/**
 * Monta o {@link AuditInput} de um CT-e consultando as {@code api}s de documents, freight, quotes e tenancy e o índice
 * de NF-es da auditoria. Tudo na transação corrente e no tenant do contexto.
 */
@Component
public class AuditInputAssembler {

    /** Entrada do motor mais os dados do CT-e e da transportadora usados nas colunas do caso. */
    public record Assembled(AuditInput input, CanonicalCte cte, CarrierRef carrier, List<String> referencedNfeKeys) {
    }

    private final DocumentCatalog documents;
    private final CarrierDirectory carriers;
    private final FreightCalculation freight;
    private final QuoteLookup quotes;
    private final TenantDirectory tenants;
    private final PolicyService policies;
    private final CaseRepository cases;

    public AuditInputAssembler(DocumentCatalog documents, CarrierDirectory carriers, FreightCalculation freight,
            QuoteLookup quotes, TenantDirectory tenants, PolicyService policies, CaseRepository cases) {
        this.documents = documents;
        this.carriers = carriers;
        this.freight = freight;
        this.quotes = quotes;
        this.tenants = tenants;
        this.policies = policies;
        this.cases = cases;
    }

    /** CT-e normalizado (modelo canônico) do documento. @throws AuditException se o documento não é um CT-e processado */
    public CanonicalCte cteOf(UUID cteDocumentId) {
        CanonicalDocument document = documents.find(cteDocumentId)
                .orElseThrow(() -> AuditException.notFound("Documento do CT-e não encontrado."));
        if (document.cte() == null) {
            throw AuditException.conflict("CTE_NOT_PROCESSED", "O CT-e ainda não foi normalizado.");
        }
        return document.cte();
    }

    public Assembled assemble(UUID caseId, UUID cteDocumentId) {
        CanonicalCte cte = cteOf(cteDocumentId);
        String carrierCnpj = cte.getCarrier().getCnpj();
        List<String> keys = cte.getReferencedNfeKeys() == null ? List.of() : cte.getReferencedNfeKeys();

        // NF-es: recebidas (vínculo resolvido) e pendentes
        Map<String, Link> linkByKey = new LinkedHashMap<>();
        for (Link link : documents.linksFrom(cteDocumentId)) {
            if (link.type() == DocumentCatalog.LinkType.CTE_NFE) {
                linkByKey.put(link.targetKey(), link);
            }
        }
        List<Nfe> nfes = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        List<CanonicalNfe> nfeCanonicals = new ArrayList<>();
        Map<String, UUID> nfeDocumentByKey = new LinkedHashMap<>();
        for (String key : keys) {
            Link link = linkByKey.get(key);
            Optional<CanonicalDocument> nfeDoc = link != null && link.resolved() && link.targetDocumentId() != null
                    ? documents.find(link.targetDocumentId()) : Optional.empty();
            if (nfeDoc.isEmpty() || nfeDoc.get().nfe() == null) {
                pending.add(key);
                continue;
            }
            CanonicalNfe nfe = nfeDoc.get().nfe();
            nfeCanonicals.add(nfe);
            nfeDocumentByKey.put(key, nfeDoc.get().id());
            nfes.add(new Nfe(key, nfe.getTotalGrossWeightKg(), nfe.getTotalAmount(), freightMode(nfe),
                    nfe.getCarrierCnpj(), nfeDoc.get().lifecycle() == DocumentCatalog.Lifecycle.CANCELLED));
        }

        CarrierRef carrier = carriers.findByCnpj(carrierCnpj).orElse(null);
        AuditPolicy policy = policies.effective(carrier == null ? null : carrier.id());
        Freight freightResult = freight(cte, carrier, nfeCanonicals);
        Quote quote = quote(carrierCnpj, keys, pending, nfeDocumentByKey);

        String root = Cnpj.of(carrierCnpj).root();
        List<EarlierCte> earlier = cases.earlierCasesReferencing(caseId, keys).stream()
                .map(o -> new EarlierCte(o.cteKey(), o.caseId().toString(), o.nfeKey(), o.carrierRoot(), o.valid()))
                .toList();

        boolean takerIsTenant = cte.getTaker() != null && cte.getTaker().getParty() != null
                && cte.getTaker().getParty().getCnpj() != null && Cnpj.isValid(cte.getTaker().getParty().getCnpj())
                && tenants.ownsCnpj(TenantContext.require(), Cnpj.of(cte.getTaker().getParty().getCnpj()));

        AuditInput.Cte cteInput = new AuditInput.Cte(cte.getAccessKey(), CteType.valueOf(cte.getType().name()),
                cte.getRelatedCteKey(), carrierCnpj, takerIsTenant, cte.getTotalAmount(),
                cte.getComponents().stream().map(c -> new ChargedComponent(c.getCode().name(), c.getAmount())).toList(),
                cte.getMeasures() == null ? null : cte.getMeasures().getActualWeightKg(), cte.getCargoValue());
        return new Assembled(new AuditInput(cteInput, nfes, pending, quote, freightResult, policy, earlier), cte, carrier, keys);
    }

    /** Seleciona a tabela vigente e calcula o frete esperado; sem transportadora cadastrada não há tabela. */
    private Freight freight(CanonicalCte cte, CarrierRef carrier, List<CanonicalNfe> nfes) {
        if (carrier == null) {
            return new NoTable();
        }
        String destinationCep = cte.getDestination() == null ? null : cte.getDestination().getCep();
        if (destinationCep == null) {
            return new RouteNotCovered(null, 0);
        }
        BigDecimal actual = cte.getMeasures() != null && cte.getMeasures().getActualWeightKg() != null
                ? cte.getMeasures().getActualWeightKg()
                : nfes.stream().map(CanonicalNfe::getTotalGrossWeightKg).filter(w -> w != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cargo = cte.getCargoValue() != null ? cte.getCargoValue()
                : nfes.stream().map(CanonicalNfe::getTotalAmount).filter(v -> v != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal icmsRate = cte.getIcms() == null ? null : cte.getIcms().getRate();
        var shipment = new FreightCalculation.Shipment(destinationCep, null, actual,
                cte.getMeasures() == null ? null : cte.getMeasures().getCubedWeightKg(),
                cte.getMeasures() == null ? null : cte.getMeasures().getVolumeM3(), cargo, icmsRate);
        OffsetDateTime issuedAt = cte.getIssuedAt();
        return switch (freight.calculate(carrier.id(), issuedAt, shipment)) {
            case FreightCalculation.NoTable n -> new NoTable();
            case FreightCalculation.RouteNotCovered r -> new RouteNotCovered(r.tableId().toString(), r.tableVersion());
            case FreightCalculation.Calculated c -> new Calculated(c.tableId().toString(), c.tableVersion(), c.total(),
                    c.components().stream().map(x -> new ChargedComponent(x.code(), x.amount())).toList());
        };
    }

    /** Soma das cotações aplicáveis das NF-es; só existe quando todas as NF-es chegaram e todas têm cotação. */
    private Quote quote(String carrierCnpj, List<String> keys, List<String> pending, Map<String, UUID> docByKey) {
        if (keys.isEmpty() || !pending.isEmpty()) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        List<String> ids = new ArrayList<>();
        for (String key : keys) {
            var quote = quotes.applicableQuote(docByKey.get(key), carrierCnpj);
            if (quote.isEmpty()) {
                return null;
            }
            total = total.add(quote.get().amount());
            ids.add(quote.get().quoteId().toString());
        }
        return new Quote(total, ids);
    }

    private static FreightMode freightMode(CanonicalNfe nfe) {
        return nfe.getFreightMode() == null ? null : FreightMode.valueOf(nfe.getFreightMode().name());
    }
}

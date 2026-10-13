package br.com.easyaudit.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import br.com.easyaudit.audit.domain.AuditEngine;
import br.com.easyaudit.audit.domain.AuditInput;
import br.com.easyaudit.audit.domain.AuditInput.Calculated;
import br.com.easyaudit.audit.domain.AuditInput.ChargedComponent;
import br.com.easyaudit.audit.domain.AuditInput.Cte;
import br.com.easyaudit.audit.domain.AuditInput.CteType;
import br.com.easyaudit.audit.domain.AuditInput.EarlierCte;
import br.com.easyaudit.audit.domain.AuditInput.Freight;
import br.com.easyaudit.audit.domain.AuditInput.FreightMode;
import br.com.easyaudit.audit.domain.AuditInput.Nfe;
import br.com.easyaudit.audit.domain.AuditInput.NoTable;
import br.com.easyaudit.audit.domain.AuditInput.Quote;
import br.com.easyaudit.audit.domain.AuditInput.RouteNotCovered;
import br.com.easyaudit.audit.domain.AuditOutcome;
import br.com.easyaudit.audit.domain.AuditOutcome.Classification;
import br.com.easyaudit.audit.domain.AuditOutcome.ReferenceSource;
import br.com.easyaudit.audit.domain.AuditOutcome.Result;
import br.com.easyaudit.audit.domain.AuditPolicy;
import org.junit.jupiter.api.Test;

/** Reproduz os cenários da spec freight-audit sobre o motor puro. */
class AuditEngineTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    /** Montador de entradas com valores padrão: CT-e normal da transportadora 11222333, tomador = tenant. */
    static class Builder {
        String cteKey = "35261011222333000181570010000001231123456780";
        CteType type = CteType.NORMAL;
        String related;
        String carrier = "11222333000181";
        boolean takerIsTenant = true;
        String charged = "66.02";
        List<ChargedComponent> components = new ArrayList<>();
        String weight;
        String cargo;
        List<Nfe> nfes = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        Quote quote;
        Freight freight = new NoTable();
        AuditPolicy policy = AuditPolicy.DEFAULT;
        List<EarlierCte> earlier = new ArrayList<>();

        Builder charged(String v) { charged = v; return this; }
        Builder type(CteType t, String relatedKey) { type = t; related = relatedKey; return this; }
        Builder carrier(String c) { carrier = c; return this; }
        Builder takerIsTenant(boolean b) { takerIsTenant = b; return this; }
        Builder component(String code, String amount) { components.add(new ChargedComponent(code, n(amount))); return this; }
        Builder weight(String w) { weight = w; return this; }
        Builder cargo(String c) { cargo = c; return this; }
        Builder nfe(String key, String gross, String total, FreightMode mode, String carrierCnpj, boolean cancelled) {
            nfes.add(new Nfe(key, gross == null ? null : n(gross), n(total), mode, carrierCnpj, cancelled));
            return this;
        }
        Builder pending(String key) { pending.add(key); return this; }
        Builder quote(String total) { quote = new Quote(n(total), List.of("q1")); return this; }
        Builder table(String total, String... componentsAndAmounts) {
            List<ChargedComponent> c = new ArrayList<>();
            for (int i = 0; i < componentsAndAmounts.length; i += 2) {
                c.add(new ChargedComponent(componentsAndAmounts[i], n(componentsAndAmounts[i + 1])));
            }
            freight = new Calculated("t1", 1, n(total), c);
            return this;
        }
        Builder routeNotCovered() { freight = new RouteNotCovered("t1", 1); return this; }
        Builder policy(String abs, String pct) {
            policy = new AuditPolicy(n(abs), n(pct), true, n("5"), n("1"));
            return this;
        }
        Builder policy(AuditPolicy p) { policy = p; return this; }
        Builder earlier(String cteKey, String nfeKey, String carrierRoot, boolean valid) {
            earlier.add(new EarlierCte(cteKey, "case-1", nfeKey, carrierRoot, valid));
            return this;
        }

        AuditOutcome run() {
            Cte cte = new Cte(cteKey, type, related, carrier, takerIsTenant, n(charged), components,
                    weight == null ? null : n(weight), cargo == null ? null : n(cargo));
            return AuditEngine.evaluate(new AuditInput(cte, nfes, pending, quote, freight, policy, earlier));
        }
    }

    private static Builder audit() {
        return new Builder();
    }

    // --- valor de referência e classificação ---

    @Test
    void compliantWhenTheTableTotalMatches() {
        AuditOutcome o = audit().charged("66.02").table("66.02").run();
        assertThat(o.classification()).isEqualTo(Classification.COMPLIANT);
        assertThat(o.referenceAmount()).isEqualByComparingTo("66.02");
        assertThat(o.referenceSource()).isEqualTo(ReferenceSource.TABLE);
        assertThat(o.divergenceAmount()).isEqualByComparingTo("0.00");
        assertThat(o.check(AuditEngine.TOTAL_VS_TABLE).result()).isEqualTo(Result.PASSED);
        assertThat(o.failedBlockingChecks()).isEmpty();
    }

    @Test
    void notAuditableWithoutTableAndWithoutQuote() {
        AuditOutcome o = audit().charged("66.02").run();
        assertThat(o.classification()).isEqualTo(Classification.NOT_AUDITABLE);
        assertThat(o.reasons()).containsExactly("NO_TABLE");
        assertThat(o.referenceAmount()).isNull();
        assertThat(o.referenceSource()).isNull();
        assertThat(o.check(AuditEngine.TOTAL_VS_TABLE).result()).isEqualTo(Result.SKIPPED);
    }

    @Test
    void routeNotCoveredIsReportedAsTheReason() {
        AuditOutcome o = audit().routeNotCovered().run();
        assertThat(o.classification()).isEqualTo(Classification.NOT_AUDITABLE);
        assertThat(o.reasons()).containsExactly("ROUTE_NOT_COVERED");
    }

    @Test
    void pendingNfesAreReportedAlongsideTheOtherReasons() {
        AuditOutcome o = audit().pending("35261045723174000110550010000090011876543217").run();
        assertThat(o.reasons()).containsExactly("NO_TABLE", "PENDING_NFE");
        assertThat(o.classification()).isEqualTo(Classification.NOT_AUDITABLE);
    }

    @Test
    void onlyAQuoteIsEnoughToBeCompliant() {
        AuditOutcome o = audit().charged("64.90").quote("64.90").run();
        assertThat(o.classification()).isEqualTo(Classification.COMPLIANT);
        assertThat(o.check(AuditEngine.TOTAL_VS_QUOTE).result()).isEqualTo(Result.PASSED);
        assertThat(o.reasons()).containsExactly("NO_TABLE");
        assertThat(o.referenceSource()).isEqualTo(ReferenceSource.QUOTE);
        assertThat(o.quotedAmount()).isEqualByComparingTo("64.90");
    }

    @Test
    void withTableAndQuoteTheTableIsThePrimaryReference() {
        AuditOutcome o = audit().charged("66.02").table("66.02").quote("60.00").run();
        assertThat(o.referenceSource()).isEqualTo(ReferenceSource.TABLE);
        assertThat(o.quotedAmount()).isEqualByComparingTo("60.00");
        // 6.02 acima da cotação com política zerada: a falha bloqueante vem da cotação
        assertThat(o.failedBlockingChecks()).containsExactly(AuditEngine.TOTAL_VS_QUOTE);
        assertThat(o.classification()).isEqualTo(Classification.DIVERGENT);
    }

    // --- tolerância ---

    @Test
    void withinThePercentageTolerance() {
        AuditOutcome o = audit().charged("101.50").table("100.00").policy("1.00", "2").run();
        var check = o.check(AuditEngine.TOTAL_VS_TABLE);
        assertThat(check.result()).isEqualTo(Result.PASSED);
        assertThat((BigDecimal) check.details().get("difference")).isEqualByComparingTo("1.50");
        assertThat((BigDecimal) check.details().get("limit")).isEqualByComparingTo("2.00");
        assertThat(o.classification()).isEqualTo(Classification.COMPLIANT);
    }

    @Test
    void outsideTheToleranceFailsWithTheDivergence() {
        AuditOutcome o = audit().charged("103.00").table("100.00").policy("1.00", "2").run();
        assertThat(o.check(AuditEngine.TOTAL_VS_TABLE).result()).isEqualTo(Result.FAILED);
        assertThat(o.divergenceAmount()).isEqualByComparingTo("3.00");
        assertThat(o.failedBlockingChecks()).containsExactly(AuditEngine.TOTAL_VS_TABLE);
        assertThat(o.classification()).isEqualTo(Classification.DIVERGENT);
    }

    @Test
    void theLimitIsTheGreaterOfTheAbsoluteAndThePercentage() {
        // absoluta 5.00 maior que 2% de 100 (2.00): diferença de 4.00 passa
        assertThat(audit().charged("104.00").table("100.00").policy("5.00", "2").run()
                .check(AuditEngine.TOTAL_VS_TABLE).result()).isEqualTo(Result.PASSED);
        // exatamente no limite passa; um centavo acima falha
        assertThat(audit().charged("102.00").table("100.00").policy("1.00", "2").run()
                .check(AuditEngine.TOTAL_VS_TABLE).result()).isEqualTo(Result.PASSED);
        assertThat(audit().charged("102.01").table("100.00").policy("1.00", "2").run()
                .check(AuditEngine.TOTAL_VS_TABLE).result()).isEqualTo(Result.FAILED);
    }

    @Test
    void chargeBelowTheReferenceIsCompliantAndRecordedAsUndercharge() {
        AuditOutcome o = audit().charged("95.00").table("100.00").run();
        assertThat(o.check(AuditEngine.TOTAL_VS_TABLE).result()).isEqualTo(Result.PASSED);
        assertThat(o.underchargeAmount()).isEqualByComparingTo("5.00");
        assertThat((BigDecimal) o.check(AuditEngine.TOTAL_VS_TABLE).details().get("undercharge")).isEqualByComparingTo("5.00");
        assertThat(o.classification()).isEqualTo(Classification.COMPLIANT);
        assertThat(o.divergenceAmount()).isEqualByComparingTo("-5.00");
    }

    // --- bloqueantes ---

    @Test
    void duplicateNfeWhenAnEarlierValidNormalCteOfTheSameCarrierRootReferencesIt() {
        String nfe = "35261045723174000110550010000090011876543217";
        AuditOutcome o = audit().charged("50.00").table("50.00").earlier("CTE-C1", nfe, "11222333", true).run();
        var check = o.check(AuditEngine.DUPLICATE_NFE);
        assertThat(check.result()).isEqualTo(Result.FAILED);
        assertThat(check.details()).containsEntry("otherCteKey", "CTE-C1").containsEntry("nfeKey", nfe);
        assertThat(o.classification()).isEqualTo(Classification.DIVERGENT);
    }

    @Test
    void anotherCarriersCteOrAnInvalidOneIsNotADuplicate() {
        String nfe = "35261045723174000110550010000090011876543217";
        assertThat(audit().table("66.02").earlier("C1", nfe, "99888777", true).run().check(AuditEngine.DUPLICATE_NFE).result())
                .isEqualTo(Result.PASSED);
        assertThat(audit().table("66.02").earlier("C1", nfe, "11222333", false).run().check(AuditEngine.DUPLICATE_NFE).result())
                .isEqualTo(Result.PASSED);
    }

    @Test
    void aSubstituteCteIsNotADuplicate() {
        String nfe = "35261045723174000110550010000090011876543217";
        AuditOutcome o = audit().type(CteType.SUBSTITUTE, "CTE-C1").table("66.02")
                .earlier("CTE-C1", nfe, "11222333", true).run();
        assertThat(o.check(AuditEngine.DUPLICATE_NFE).result()).isNotEqualTo(Result.FAILED);
        assertThat(o.classification()).isEqualTo(Classification.COMPLIANT);
    }

    @Test
    void aComplementaryCteAlwaysFailsAndPointsToTheComplementedOne() {
        AuditOutcome o = audit().type(CteType.COMPLEMENT, "CTE-ORIGINAL").charged("20.00").table("66.02").run();
        var check = o.check(AuditEngine.COMPLEMENTARY_CHARGE);
        assertThat(check.result()).isEqualTo(Result.FAILED);
        assertThat(check.details()).containsEntry("complementedCteKey", "CTE-ORIGINAL");
        assertThat(o.classification()).isEqualTo(Classification.DIVERGENT);
        // o total não é comparado com a tabela para complementares
        assertThat(o.check(AuditEngine.TOTAL_VS_TABLE).result()).isEqualTo(Result.SKIPPED);
    }

    @Test
    void aCancelledNfeIsABlockingFailure() {
        AuditOutcome o = audit().table("66.02").nfe("K1", "10", "500", FreightMode.CIF, null, true).run();
        assertThat(o.check(AuditEngine.CANCELLED_NFE).result()).isEqualTo(Result.FAILED);
        assertThat(o.classification()).isEqualTo(Classification.DIVERGENT);
    }

    // --- alertas ---

    @Test
    void weightMismatchIsAnAlertAndDoesNotChangeTheClassification() {
        AuditOutcome o = audit().table("66.02").weight("120").nfe("K1", "100", "500", FreightMode.CIF, null, false).run();
        var check = o.check(AuditEngine.WEIGHT_MISMATCH);
        assertThat(check.result()).isEqualTo(Result.FAILED);
        assertThat((BigDecimal) check.details().get("cteWeightKg")).isEqualByComparingTo("120");
        assertThat((BigDecimal) check.details().get("nfeWeightKg")).isEqualByComparingTo("100");
        assertThat(o.alerts()).containsExactly(AuditEngine.WEIGHT_MISMATCH);
        assertThat(o.failedBlockingChecks()).isEmpty();
        assertThat(o.classification()).isEqualTo(Classification.COMPLIANT);
    }

    @Test
    void weightWithinTheToleranceDoesNotAlert() {
        AuditOutcome o = audit().table("66.02").weight("104").nfe("K1", "100", "500", FreightMode.CIF, null, false).run();
        assertThat(o.check(AuditEngine.WEIGHT_MISMATCH).result()).isEqualTo(Result.PASSED);
    }

    @Test
    void weightIsComparedWithTheSumOfAllNfes() {
        AuditOutcome o = audit().table("66.02").weight("5000")
                .nfe("K1", "3000", "500", FreightMode.CIF, null, false).nfe("K2", "2000", "500", FreightMode.CIF, null, false).run();
        assertThat(o.check(AuditEngine.WEIGHT_MISMATCH).result()).isEqualTo(Result.PASSED);
    }

    @Test
    void weightIsNotComparedWhileNfesArePending() {
        AuditOutcome o = audit().table("66.02").weight("120").nfe("K1", "100", "500", FreightMode.CIF, null, false)
                .pending("K2").run();
        assertThat(o.check(AuditEngine.WEIGHT_MISMATCH).result()).isEqualTo(Result.SKIPPED);
    }

    @Test
    void cargoValueMismatch() {
        AuditOutcome o = audit().table("66.02").cargo("1000").nfe("K1", "10", "900", FreightMode.CIF, null, false).run();
        assertThat(o.check(AuditEngine.CARGO_VALUE_MISMATCH).result()).isEqualTo(Result.FAILED);
        assertThat(audit().table("66.02").cargo("904").nfe("K1", "10", "900", FreightMode.CIF, null, false).run()
                .check(AuditEngine.CARGO_VALUE_MISMATCH).result()).isEqualTo(Result.PASSED);
    }

    @Test
    void carrierMismatchWhenTheNfeNamesAnotherCarrierRoot() {
        AuditOutcome o = audit().table("66.02").nfe("K1", "10", "500", FreightMode.CIF, "45723174000110", false).run();
        assertThat(o.check(AuditEngine.CARRIER_MISMATCH).result()).isEqualTo(Result.FAILED);
        // filial da mesma raiz não é divergência
        assertThat(audit().table("66.02").nfe("K1", "10", "500", FreightMode.CIF, "11222333000262", false).run()
                .check(AuditEngine.CARRIER_MISMATCH).result()).isEqualTo(Result.PASSED);
    }

    @Test
    void fobFreightChargedToTheMerchant() {
        AuditOutcome o = audit().table("66.02").nfe("K1", "10", "500", FreightMode.FOB, null, false).run();
        assertThat(o.check(AuditEngine.FOB_FREIGHT).result()).isEqualTo(Result.FAILED);
        assertThat(o.classification()).isEqualTo(Classification.COMPLIANT);
        // se o tomador não é o tenant, não há o que alertar
        assertThat(audit().table("66.02").takerIsTenant(false).nfe("K1", "10", "500", FreightMode.FOB, null, false).run()
                .check(AuditEngine.FOB_FREIGHT).result()).isEqualTo(Result.PASSED);
    }

    // --- comparação por componente ---

    @Test
    void componentComparisonShowsTheDifferencePerCodeWithoutChangingTheClassification() {
        AuditOutcome o = audit().charged("66.02").table("66.02", "FREIGHT_WEIGHT", "45.00", "GRIS", "2.00", "TOLL", "19.02")
                .component("FREIGHT_WEIGHT", "45.00").component("GRIS", "4.00").component("TOLL", "17.02").run();
        var gris = o.componentComparison().stream().filter(c -> c.code().equals("GRIS")).findFirst().orElseThrow();
        assertThat(gris.charged()).isEqualByComparingTo("4.00");
        assertThat(gris.calculated()).isEqualByComparingTo("2.00");
        assertThat(gris.difference()).isEqualByComparingTo("2.00");
        assertThat(o.classification()).isEqualTo(Classification.COMPLIANT);
    }

    @Test
    void componentsWithTheSameCodeAreSummed() {
        AuditOutcome o = audit().table("66.02", "GRIS", "5.00").component("GRIS", "2.00").component("GRIS", "3.50").run();
        var gris = o.componentComparison().stream().filter(c -> c.code().equals("GRIS")).findFirst().orElseThrow();
        assertThat(gris.charged()).isEqualByComparingTo("5.50");
        assertThat(gris.difference()).isEqualByComparingTo("0.50");
    }

    @Test
    void componentsOnlyOnOneSideAppearToo() {
        AuditOutcome o = audit().table("66.02", "TOLL", "3.10").component("OTHER", "9.00").run();
        assertThat(o.componentComparison()).extracting(c -> c.code()).containsExactlyInAnyOrder("TOLL", "OTHER");
    }

    @Test
    void noComponentComparisonWithoutATable() {
        assertThat(audit().component("GRIS", "2.00").run().componentComparison()).isEmpty();
    }
}

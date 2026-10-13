package br.com.easyaudit.quotes;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.platform.tenant.TenantContext;
import br.com.easyaudit.platform.testsupport.TwoTenants;
import br.com.easyaudit.quotes.api.QuoteLookup;
import br.com.easyaudit.quotes.application.RegisterQuote;
import br.com.easyaudit.quotes.domain.StoredQuote.Source;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Cotação aplicável a um CT-e: raiz do CNPJ da transportadora e revisão vigente mais recente. */
class QuoteLookupIT extends AbstractQuotesIT {

    @Autowired
    RegisterQuote register;
    @Autowired
    QuoteLookup lookup;

    private RegisterQuote.Receipt quote(String externalId, String carrierCnpj, String amount, String quotedAt, String nfeKey)
            throws Exception {
        return TenantContext.callAs(TwoTenants.A, () -> register.register(new RegisterQuote.Command(Source.VTEX, externalId,
                carrierCnpj, "Rodoviário", new BigDecimal(amount), OffsetDateTime.parse(quotedAt), "20040020", null,
                null, null, nfeKey, null)));
    }

    private java.util.Optional<QuoteLookup.ApplicableQuote> applicable(UUID nfeDoc, String cteIssuer) throws Exception {
        return TenantContext.callAs(TwoTenants.A, () -> lookup.applicableQuote(nfeDoc, cteIssuer));
    }

    @Test
    void aQuoteOfAnotherCarrierIsNotApplicable() throws Exception {
        var nfe = NfeFixture.builder().number(51).build();
        UUID doc = processNfe(TwoTenants.A, nfe);
        quote("Q-A", "11222333000181", "39.90", "2026-10-09T10:00:00-03:00", nfe.accessKey()); // transportadora A

        // CT-e emitido pela transportadora B (raiz 45723174)
        assertThat(applicable(doc, "45723174000110")).isEmpty();
        assertThat(applicable(doc, "11222333000181")).isPresent();
    }

    @Test
    void aBranchOfTheQuotedCarrierUsesTheHeadOfficeQuote() throws Exception {
        var nfe = NfeFixture.builder().number(52).build();
        UUID doc = processNfe(TwoTenants.A, nfe);
        quote("Q-matriz", "11222333000181", "39.90", "2026-10-09T10:00:00-03:00", nfe.accessKey());

        // filial 0002 da mesma raiz 11222333
        var branch = applicable(doc, "11222333000262");
        assertThat(branch).isPresent();
        assertThat(branch.get().amount()).isEqualByComparingTo("39.90");
        assertThat(branch.get().revision()).isEqualTo(1);
    }

    @Test
    void theMostRecentQuoteByQuotedAtWins() throws Exception {
        var nfe = NfeFixture.builder().number(53).build();
        UUID doc = processNfe(TwoTenants.A, nfe);
        quote("Q-old", "11222333000181", "50.00", "2026-10-08T10:00:00-03:00", nfe.accessKey());
        quote("Q-new", "11222333000262", "45.00", "2026-10-09T12:00:00-03:00", nfe.accessKey());
        quote("Q-older", "11222333000181", "60.00", "2026-10-01T10:00:00-03:00", nfe.accessKey());

        var result = applicable(doc, "11222333000181");
        assertThat(result.orElseThrow().amount()).isEqualByComparingTo("45.00");
    }

    @Test
    void theCurrentRevisionValuesAreUsed() throws Exception {
        var nfe = NfeFixture.builder().number(54).build();
        UUID doc = processNfe(TwoTenants.A, nfe);
        quote("Q-rev", "11222333000181", "39.90", "2026-10-09T10:00:00-03:00", nfe.accessKey());
        quote("Q-rev", "11222333000181", "42.50", "2026-10-09T10:00:00-03:00", nfe.accessKey());

        var result = applicable(doc, "11222333000181").orElseThrow();
        assertThat(result.revision()).isEqualTo(2);
        assertThat(result.amount()).isEqualByComparingTo("42.50");
    }

    @Test
    void unlinkedQuotesAndOtherTenantsAreIgnored() throws Exception {
        var nfe = NfeFixture.builder().number(55).build();
        UUID doc = processNfe(TwoTenants.A, nfe);
        // cotação para outra chave: sem vínculo com este documento
        quote("Q-other", "11222333000181", "10.00", "2026-10-09T10:00:00-03:00", NfeFixture.builder().number(56).build().accessKey());
        assertThat(applicable(doc, "11222333000181")).isEmpty();

        quote("Q-mine", "11222333000181", "20.00", "2026-10-09T10:00:00-03:00", nfe.accessKey());
        assertThat(TenantContext.callAs(TwoTenants.B, () -> lookup.applicableQuote(doc, "11222333000181"))).isEmpty();
    }
}

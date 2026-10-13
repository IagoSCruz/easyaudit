package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.easyaudit.documents.fixtures.CancellationFixture;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.FiscalXml;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.documents.fixtures.XmlSigner;
import br.com.easyaudit.shared.AccessKey;
import org.junit.jupiter.api.Test;

/** As fixtures geradas têm chave de acesso válida e assinatura RSA-SHA1 que o JDK valida. */
class FixturesSignatureTest {

    @Test
    void cteFixtureIsSignedAndHasAValidAccessKey() {
        FiscalXml cte = CteFixture.builder().build();
        assertThat(AccessKey.isValid(cte.accessKey())).isTrue();
        assertThat(new AccessKey(cte.accessKey()).isCte()).isTrue();
        assertThat(cte.text()).contains("<Signature").contains("rsa-sha1").contains("<X509Certificate>")
                .contains("<cteProc").contains("<cStat>100</cStat>");
        assertThat(XmlSigner.validates(cte.bytes(), "infCte")).isTrue();
    }

    @Test
    void nfeFixtureIsSignedAndHasAValidAccessKey() {
        FiscalXml nfe = NfeFixture.builder().volume(1, "3.000", "3.500").build();
        assertThat(new AccessKey(nfe.accessKey()).isNfe()).isTrue();
        assertThat(XmlSigner.validates(nfe.bytes(), "infNFe")).isTrue();
    }

    @Test
    void cancellationFixturesAreSigned() {
        FiscalXml cteCancel = CancellationFixture.forCte(CteFixture.builder().build().accessKey(), "11222333000181").build();
        FiscalXml nfeCancel = CancellationFixture.forNfe(NfeFixture.builder().build().accessKey(), "45723174000110").build();
        assertThat(cteCancel.text()).contains("<procEventoCTe").contains("<tpEvento>110111</tpEvento>");
        assertThat(nfeCancel.text()).contains("<procEventoNFe").contains("<tpEvento>110111</tpEvento>");
        assertThat(XmlSigner.validates(cteCancel.bytes(), "infEvento")).isTrue();
        assertThat(XmlSigner.validates(nfeCancel.bytes(), "infEvento")).isTrue();
    }

    @Test
    void tamperingAfterSigningBreaksTheSignature() {
        FiscalXml cte = CteFixture.builder().build().replacing("<vTPrest>152.37</vTPrest>", "<vTPrest>1.00</vTPrest>");
        assertThat(XmlSigner.validates(cte.bytes(), "infCte")).isFalse();
    }

    @Test
    void variationsAreGenerated() {
        assertThat(CteFixture.builder().version("3.00").build().text()).contains("versao=\"3.00\"");
        assertThat(CteFixture.builder().withoutProtocol().build().text()).doesNotContain("<protCTe");
        assertThat(CteFixture.builder().environment(2).build().text()).contains("<tpAmb>2</tpAmb>");
        assertThat(CteFixture.builder().unknownTaxGroup().build().text()).contains("<infTribFed>");
        assertThat(CteFixture.builder().unsigned().build().text()).doesNotContain("<Signature");
    }
}

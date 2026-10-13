package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.documents.infrastructure.xml.layout.CteLayout;
import br.com.easyaudit.documents.infrastructure.xml.layout.LayoutReader;
import br.com.easyaudit.documents.infrastructure.xml.layout.NfeLayout;
import org.junit.jupiter.api.Test;

class LayoutReaderTest {

    @Test
    void readsTheCteSubset() {
        var fixture = CteFixture.builder()
                .component("FRETE PESO", "100.00").component("GRIS", "52.37")
                .quantity("01", "PESO BRUTO", "12.5").quantity("01", "Peso Cubado", "18.0")
                .nfeKey("35261045723174000110550010000090011876543217");
        CteLayout cte = LayoutReader.readCte(fixture.build().bytes());

        var inf = cte.CTe().infCte();
        assertThat(inf.Id()).isEqualTo("CTe" + fixture.accessKey());
        assertThat(inf.versao()).isEqualTo("4.00");
        assertThat(inf.ide().nCT()).isEqualTo("123");
        assertThat(inf.ide().tpCTe()).isEqualTo("0");
        assertThat(inf.ide().toma3().toma()).isEqualTo("0");
        assertThat(inf.emit().CNPJ()).isEqualTo("11222333000181");
        assertThat(inf.dest().address().CEP()).isEqualTo("20040020");
        assertThat(inf.vPrest().vTPrest()).isEqualTo("152.37");
        assertThat(inf.vPrest().Comp()).extracting(CteLayout.Comp::xNome).containsExactly("FRETE PESO", "GRIS");
        assertThat(inf.imp().ICMS().data().vICMS()).isEqualTo("18.28");
        assertThat(inf.infCTeNorm().infCarga().infQ()).extracting(CteLayout.InfQ::tpMed)
                .containsExactly("PESO BRUTO", "Peso Cubado");
        assertThat(inf.infCTeNorm().infDoc().infNFe()).hasSize(1);
        assertThat(cte.protCTe().infProt().cStat()).isEqualTo("100");
    }

    @Test
    void readsComplementAndSubstituteReferences() {
        String original = CteFixture.builder().number(1).build().accessKey();
        CteLayout complement = LayoutReader.readCte(CteFixture.builder().type(CteFixture.Type.COMPLEMENT)
                .relatedKey(original).number(2).build().bytes());
        assertThat(complement.CTe().infCte().infCteComp().chCTe()).isEqualTo(original);

        CteLayout substitute = LayoutReader.readCte(CteFixture.builder().type(CteFixture.Type.SUBSTITUTE)
                .relatedKey(original).number(3).build().bytes());
        assertThat(substitute.CTe().infCte().infCTeNorm().infCteSub().chCte()).isEqualTo(original);
    }

    @Test
    void toleratesUnknownTaxGroupsAndKeepsTheRest() {
        CteLayout cte = LayoutReader.readCte(CteFixture.builder().unknownTaxGroup().build().bytes());
        assertThat(cte.CTe().infCte().vPrest().vTPrest()).isEqualTo("152.37");
        assertThat(cte.CTe().infCte().imp().ICMS().data().CST()).isEqualTo("00");
    }

    @Test
    void readsTheNfeSubset() {
        var fixture = NfeFixture.builder().volume(1, "3.000", "3.500").volume(2, "1.000", "1.250")
                .orderReference("PED-123").amounts("5000.00", "4950.00", "50.00");
        NfeLayout nfe = LayoutReader.readNfe(fixture.build().bytes());

        var inf = nfe.NFe().infNFe();
        assertThat(inf.Id()).isEqualTo("NFe" + fixture.accessKey());
        assertThat(inf.emit().CNPJ()).isEqualTo("45723174000110");
        assertThat(inf.dest().enderDest().CEP()).isEqualTo("20040020");
        assertThat(inf.total().ICMSTot().vNF()).isEqualTo("5000.00");
        assertThat(inf.det().get(0).prod().xPed()).isEqualTo("PED-123");
        assertThat(inf.transp().modFrete()).isEqualTo("0");
        assertThat(inf.transp().vol()).extracting(NfeLayout.Vol::pesoB).containsExactly("3.500", "1.250");
        assertThat(nfe.protNFe().infProt().cStat()).isEqualTo("100");
    }

    @Test
    void missingValuesGroupDoesNotFailTheParse() {
        CteLayout cte = LayoutReader.readCte(CteFixture.builder().withoutValuesGroup().build().bytes());
        assertThat(cte.CTe().infCte().vPrest()).isNull();
    }

    @Test
    void rejectsXmlThatIsNotTheLayout() {
        assertThatThrownBy(() -> LayoutReader.readCte("não é xml".getBytes()))
                .isInstanceOf(LayoutReader.LayoutException.class);
    }
}

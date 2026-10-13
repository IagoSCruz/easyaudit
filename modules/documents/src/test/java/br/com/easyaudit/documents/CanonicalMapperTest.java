package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import br.com.easyaudit.contracts.api.model.CanonicalCte;
import br.com.easyaudit.contracts.api.model.CanonicalNfe;
import br.com.easyaudit.contracts.api.model.ComponentCode;
import br.com.easyaudit.contracts.api.model.CteType;
import br.com.easyaudit.contracts.api.model.FreightMode;
import br.com.easyaudit.contracts.api.model.TakerRole;
import br.com.easyaudit.documents.fixtures.Cnpjs;
import br.com.easyaudit.documents.fixtures.CteFixture;
import br.com.easyaudit.documents.fixtures.NfeFixture;
import br.com.easyaudit.documents.infrastructure.xml.CanonicalMapper;
import br.com.easyaudit.documents.infrastructure.xml.NormalizationException;
import br.com.easyaudit.documents.infrastructure.xml.layout.LayoutReader;
import org.junit.jupiter.api.Test;

class CanonicalMapperTest {

    private static CanonicalCte cte(CteFixture fixture) {
        return CanonicalMapper.toCte(LayoutReader.readCte(fixture.build().bytes()), (cnpj, name) -> ComponentCode.OTHER);
    }

    private static CanonicalNfe nfe(NfeFixture fixture) {
        return CanonicalMapper.toNfe(LayoutReader.readNfe(fixture.build().bytes()));
    }

    @Test
    void normalCteWithTwoComponentsAndOneNfe() {
        String nfeKey = NfeFixture.builder().build().accessKey();
        var fixture = CteFixture.builder().totalAmount("152.37")
                .component("FRETE PESO", "100.00").component("GRIS", "52.37").nfeKey(nfeKey);
        CanonicalCte cte = cte(fixture);

        assertThat(cte.getType()).isEqualTo(CteType.NORMAL);
        assertThat(cte.getAccessKey()).isEqualTo(fixture.accessKey());
        assertThat(cte.getTotalAmount()).isEqualByComparingTo("152.37");
        assertThat(cte.getReceivableAmount()).isEqualByComparingTo("152.37");
        assertThat(cte.getComponents()).hasSize(2);
        assertThat(cte.getComponents().get(0).getOriginalName()).isEqualTo("FRETE PESO");
        assertThat(cte.getComponents().get(0).getAmount()).isEqualByComparingTo("100.00");
        assertThat(cte.getReferencedNfeKeys()).containsExactly(nfeKey);
        assertThat(cte.getSchemaVersion()).isEqualTo(1);
        assertThat(cte.getIssuedAt().toString()).startsWith("2026-10-10T10:15:30");
        assertThat(cte.getCarrier().getCnpj()).isEqualTo("11222333000181");
        assertThat(cte.getCarrier().getName()).isEqualTo("TRANSPORTADORA EXEMPLO LTDA");
        assertThat(cte.getDestination().getUf()).isEqualTo("RJ");
        assertThat(cte.getDestination().getIbgeCode()).isEqualTo("3304557");
        assertThat(cte.getDestination().getCep()).isEqualTo("20040020");
        assertThat(cte.getIcms().getAmount()).isEqualByComparingTo("18.28");
        assertThat(cte.getCargoValue()).isEqualByComparingTo("5000.00");
    }

    @Test
    void takerAndRole() {
        CanonicalCte bySender = cte(CteFixture.builder());
        assertThat(bySender.getTaker().getRole()).isEqualTo(TakerRole.SENDER);
        assertThat(bySender.getTaker().getParty().getCnpj()).isEqualTo(Cnpjs.of("45723174", "0001"));

        CanonicalCte other = cte(CteFixture.builder().otherTaker(Cnpjs.of("10000004", "0001")));
        assertThat(other.getTaker().getRole()).isEqualTo(TakerRole.OTHER);
        assertThat(other.getTaker().getParty().getCnpj()).isEqualTo(Cnpjs.of("10000004", "0001"));
    }

    @Test
    void complementAndSubstituteKeepTheRelatedKey() {
        String original = CteFixture.builder().number(1).build().accessKey();
        CanonicalCte complement = cte(CteFixture.builder().type(CteFixture.Type.COMPLEMENT).relatedKey(original).number(2));
        assertThat(complement.getType()).isEqualTo(CteType.COMPLEMENT);
        assertThat(complement.getRelatedCteKey()).isEqualTo(original);
        CanonicalCte substitute = cte(CteFixture.builder().type(CteFixture.Type.SUBSTITUTE).relatedKey(original).number(3));
        assertThat(substitute.getType()).isEqualTo(CteType.SUBSTITUTE);
        assertThat(substitute.getRelatedCteKey()).isEqualTo(original);
    }

    @Test
    void grossAndCubedWeightsFromInfQ() {
        CanonicalCte cte = cte(CteFixture.builder().quantity("01", "PESO BRUTO", "12.5").quantity("01", "Peso Cubado", "18.0"));
        assertThat(cte.getMeasures().getActualWeightKg()).isEqualByComparingTo("12.5");
        assertThat(cte.getMeasures().getCubedWeightKg()).isEqualByComparingTo("18.0");
        assertThat(cte.getMeasures().getVolumeM3()).isNull();
    }

    @Test
    void tonsAreConvertedToKg() {
        CanonicalCte cte = cte(CteFixture.builder().quantity("02", "PESO BRUTO", "1.2"));
        assertThat(cte.getMeasures().getActualWeightKg()).isEqualByComparingTo("1200");
    }

    @Test
    void cubicMetersBecomeVolume() {
        CanonicalCte cte = cte(CteFixture.builder().quantity("00", "VOLUME", "0.35"));
        assertThat(cte.getMeasures().getVolumeM3()).isEqualByComparingTo("0.35");
    }

    @Test
    void measureTypeIsMatchedWithoutCaseOrAccents() {
        CanonicalCte cte = cte(CteFixture.builder().quantity("01", "peso cubádo", "20").quantity("01", "Peso Real", "9")
                .quantity("01", "PESO AFERIDO", "11"));
        assertThat(cte.getMeasures().getCubedWeightKg()).isEqualByComparingTo("20");
        assertThat(cte.getMeasures().getActualWeightKg()).isEqualByComparingTo("11"); // o maior entre os reais
    }

    @Test
    void withoutAClassifiedRealWeightTheFirstNonCubedKgWeightIsUsed() {
        CanonicalCte cte = cte(CteFixture.builder().quantity("03", "UNIDADE", "5").quantity("01", "PESO", "7.5")
                .quantity("01", "PESO CUBADO", "30"));
        assertThat(cte.getMeasures().getActualWeightKg()).isEqualByComparingTo("7.5");
        assertThat(cte.getMeasures().getCubedWeightKg()).isEqualByComparingTo("30");
    }

    @Test
    void missingValuesGroupFailsNamingTheField() {
        assertThatThrownBy(() -> cte(CteFixture.builder().withoutValuesGroup()))
                .isInstanceOf(NormalizationException.class).hasMessageContaining("vPrest");
    }

    @Test
    void unsupportedCteTypeFails() {
        var layout = LayoutReader.readCte(CteFixture.builder().build().bytes());
        var patched = new br.com.easyaudit.documents.infrastructure.xml.layout.CteLayout.Ide(
                layout.CTe().infCte().ide().mod(), "1", "1", "2026-10-10T10:00:00-03:00", "1", "2", "1", "A", "SP", "2", "B", "RJ",
                layout.CTe().infCte().ide().toma3(), null);
        var inf = layout.CTe().infCte();
        var withAnnulment = new br.com.easyaudit.documents.infrastructure.xml.layout.CteLayout(
                new br.com.easyaudit.documents.infrastructure.xml.layout.CteLayout.CTe(
                        new br.com.easyaudit.documents.infrastructure.xml.layout.CteLayout.InfCte(inf.Id(), inf.versao(),
                                patched, inf.emit(), inf.rem(), inf.exped(), inf.receb(), inf.dest(), inf.vPrest(),
                                inf.imp(), inf.infCTeNorm(), inf.infCteComp())), layout.protCTe());
        assertThatThrownBy(() -> CanonicalMapper.toCte(withAnnulment, (c, n) -> ComponentCode.OTHER))
                .isInstanceOf(NormalizationException.class).hasMessageContaining("tpCTe=2");
    }

    @Test
    void nfeWithTwoVolumesSumsTheGrossWeight() {
        CanonicalNfe nfe = nfe(NfeFixture.builder().volume(1, "3.000", "3.500").volume(2, "1.000", "1.250")
                .orderReference("PED-1").carrierCnpj("11222333000181").freightMode(0));
        assertThat(nfe.getVolumes()).hasSize(2);
        assertThat(nfe.getTotalGrossWeightKg()).isEqualByComparingTo("4.750");
        assertThat(nfe.getTotalNetWeightKg()).isEqualByComparingTo("4.000");
        assertThat(nfe.getOrderReferences()).containsExactly("PED-1");
        assertThat(nfe.getFreightMode()).isEqualTo(FreightMode.CIF);
        assertThat(nfe.getCarrierCnpj()).isEqualTo("11222333000181");
        assertThat(nfe.getTotalAmount()).isEqualByComparingTo("5000.00");
        assertThat(nfe.getFreightAmount()).isEqualByComparingTo("50.00");
        assertThat(nfe.getRecipient().getPlace().getCep()).isEqualTo("20040020");
        assertThat(nfe.getIssuer().getCnpj()).isEqualTo(Cnpjs.of("45723174", "0001"));
    }

    @Test
    void nfeFreightModes() {
        assertThat(nfe(NfeFixture.builder().freightMode(1)).getFreightMode()).isEqualTo(FreightMode.FOB);
        assertThat(nfe(NfeFixture.builder().freightMode(2)).getFreightMode()).isEqualTo(FreightMode.THIRD_PARTY);
        assertThat(nfe(NfeFixture.builder().freightMode(9)).getFreightMode()).isEqualTo(FreightMode.NONE);
    }

    @Test
    void nfeToACpfRecipientKeepsTheCpf() {
        CanonicalNfe nfe = nfe(NfeFixture.builder().recipientCpf("12345678909"));
        assertThat(nfe.getRecipient().getParty().getCpf()).isEqualTo("12345678909");
        assertThat(nfe.getRecipient().getParty().getCnpj()).isNull();
        assertThat(new BigDecimal("1")).isNotNull();
    }
}

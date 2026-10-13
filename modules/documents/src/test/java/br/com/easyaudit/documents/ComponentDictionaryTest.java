package br.com.easyaudit.documents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import br.com.easyaudit.contracts.api.model.ComponentCode;
import br.com.easyaudit.documents.domain.ComponentDictionary;
import org.junit.jupiter.api.Test;

class ComponentDictionaryTest {

    @Test
    void defaultSynonymsClassifyRegardlessOfCaseAccentsAndSeparators() {
        assertThat(ComponentDictionary.classify("FRETE PESO")).isEqualTo(ComponentCode.FREIGHT_WEIGHT);
        assertThat(ComponentDictionary.classify("Frete-Peso")).isEqualTo(ComponentCode.FREIGHT_WEIGHT);
        assertThat(ComponentDictionary.classify("frete peso")).isEqualTo(ComponentCode.FREIGHT_WEIGHT);
        assertThat(ComponentDictionary.classify("Pedágio")).isEqualTo(ComponentCode.TOLL);
        assertThat(ComponentDictionary.classify("AD VALOREM")).isEqualTo(ComponentCode.AD_VALOREM);
        assertThat(ComponentDictionary.classify("Taxa de Despacho")).isEqualTo(ComponentCode.DISPATCH_FEE);
        assertThat(ComponentDictionary.classify("GRIS")).isEqualTo(ComponentCode.GRIS);
        assertThat(ComponentDictionary.classify("TAXA DE DIFÍCIL ENTREGA")).isEqualTo(ComponentCode.TDE);
        assertThat(ComponentDictionary.classify("TDA")).isEqualTo(ComponentCode.TDA);
        assertThat(ComponentDictionary.classify("ICMS")).isEqualTo(ComponentCode.ICMS);
    }

    @Test
    void carrierAliasesPrevailOverTheDefaults() {
        Map<String, String> aliases = Map.of("TX EMERGENCIAL", "TDE", "FRETE PESO", "TOLL");
        assertThat(ComponentDictionary.classify("TX EMERGENCIAL", aliases)).isEqualTo(ComponentCode.TDE);
        assertThat(ComponentDictionary.classify("tx. emergencial", aliases)).isEqualTo(ComponentCode.TDE);
        // o sinônimo do tenant ganha do dicionário padrão
        assertThat(ComponentDictionary.classify("FRETE PESO", aliases)).isEqualTo(ComponentCode.TOLL);
        // sem sinônimo, cai no dicionário padrão
        assertThat(ComponentDictionary.classify("GRIS", aliases)).isEqualTo(ComponentCode.GRIS);
    }

    @Test
    void unknownNamesBecomeOther() {
        assertThat(ComponentDictionary.classify("TAXA XPTO")).isEqualTo(ComponentCode.OTHER);
        assertThat(ComponentDictionary.classify("TAXA XPTO", Map.of())).isEqualTo(ComponentCode.OTHER);
        assertThat(ComponentDictionary.classify("")).isEqualTo(ComponentCode.OTHER);
    }

    @Test
    void theDictionaryIsVersioned() {
        assertThat(ComponentDictionary.VERSION).isNotBlank();
    }
}

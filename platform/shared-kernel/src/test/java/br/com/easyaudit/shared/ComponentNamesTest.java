package br.com.easyaudit.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ComponentNamesTest {

    @Test
    void normalizesCaseAccentsAndSeparators() {
        assertThat(ComponentNames.normalize("FRETE PESO")).isEqualTo("FRETE PESO");
        assertThat(ComponentNames.normalize("Frete-Peso")).isEqualTo("FRETE PESO");
        assertThat(ComponentNames.normalize("  frete   peso ")).isEqualTo("FRETE PESO");
        assertThat(ComponentNames.normalize("Pedágio")).isEqualTo("PEDAGIO");
        assertThat(ComponentNames.normalize("TX. EMERGÊNCIAL/ADIC.")).isEqualTo("TX EMERGENCIAL ADIC");
        assertThat(ComponentNames.normalize(null)).isEmpty();
        assertThat(ComponentNames.normalize("---")).isEmpty();
    }
}

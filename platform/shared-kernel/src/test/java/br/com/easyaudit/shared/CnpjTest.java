package br.com.easyaudit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CnpjTest {

    @Test
    void acceptsNumericWithMask() {
        Cnpj cnpj = Cnpj.of("11.222.333/0001-81");
        assertThat(cnpj.value()).isEqualTo("11222333000181");
        assertThat(cnpj.root()).isEqualTo("11222333");
    }

    @Test
    void acceptsOfficialAlphanumericExample() {
        Cnpj cnpj = Cnpj.of("12.ABC.345/01DE-35");
        assertThat(cnpj.value()).isEqualTo("12ABC34501DE35");
        assertThat(cnpj.root()).isEqualTo("12ABC345");
    }

    @Test
    void acceptsLowercaseLetters() {
        assertThat(Cnpj.of("12abc34501de35").value()).isEqualTo("12ABC34501DE35");
    }

    @Test
    void rejectsWrongCheckDigit() {
        assertThat(Cnpj.isValid("11222333000182")).isFalse();
        assertThat(Cnpj.isValid("12ABC34501DE36")).isFalse();
        assertThatThrownBy(() -> Cnpj.of("11.222.333/0001-82")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBadShape() {
        assertThat(Cnpj.isValid(null)).isFalse();
        assertThat(Cnpj.isValid("1122233300018")).isFalse();
        assertThat(Cnpj.isValid("00000000000000")).isFalse();
        assertThat(Cnpj.isValid("12ABC34501DEAB")).isFalse();
    }
}

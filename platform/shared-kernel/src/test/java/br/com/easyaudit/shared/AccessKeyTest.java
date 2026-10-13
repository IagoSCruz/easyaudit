package br.com.easyaudit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccessKeyTest {

    private static final String FIRST_43 = "3526" + "10" + "11222333000181" + "57" + "001" + "000000123" + "1" + "12345678";

    private static String validKey() {
        return FIRST_43 + AccessKey.checkDigit(FIRST_43);
    }

    @Test
    void parsesValidKey() {
        AccessKey key = AccessKey.of(validKey());
        assertThat(key.isCte()).isTrue();
        assertThat(key.isNfe()).isFalse();
        assertThat(key.uf()).isEqualTo("SP");
        assertThat(key.yearMonth()).isEqualTo("2610");
        assertThat(key.issuerCnpj().value()).isEqualTo("11222333000181");
        assertThat(key.series()).isEqualTo("001");
        assertThat(key.number()).isEqualTo("000000123");
    }

    @Test
    void acceptsSpacesBetweenGroups() {
        String k = validKey();
        String spaced = k.substring(0, 4) + " " + k.substring(4, 8) + " " + k.substring(8);
        assertThat(AccessKey.of(spaced).value()).isEqualTo(k);
    }

    @Test
    void rejectsWrongCheckDigit() {
        String k = validKey();
        int wrong = (k.charAt(43) - '0' + 1) % 10;
        String bad = k.substring(0, 43) + wrong;
        assertThat(AccessKey.isValid(bad)).isFalse();
        assertThatThrownBy(() -> new AccessKey(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBadLengthNonDigitsAndUnknownUf() {
        assertThat(AccessKey.isValid(null)).isFalse();
        assertThat(AccessKey.isValid("123")).isFalse();
        assertThat(AccessKey.isValid(validKey().substring(0, 43) + "X")).isFalse();
        String unknownUf = "99" + FIRST_43.substring(2);
        assertThat(AccessKey.isValid(unknownUf + AccessKey.checkDigit(unknownUf))).isFalse();
    }

    @Test
    void recognizesNfeModel() {
        String nfe = "3526" + "10" + "11222333000181" + "55" + "001" + "000000123" + "1" + "12345678";
        assertThat(new AccessKey(nfe + AccessKey.checkDigit(nfe)).isNfe()).isTrue();
    }
}

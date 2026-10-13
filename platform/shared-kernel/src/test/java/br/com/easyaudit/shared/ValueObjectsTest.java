package br.com.easyaudit.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ValueObjectsTest {

    @Test
    void cepNormalizesMaskAndValidates() {
        assertThat(Cep.of("01310-100").value()).isEqualTo("01310100");
        assertThat(Cep.of("01310-100").asNumber()).isEqualTo(1310100);
        assertThat(Cep.of("01000000")).isLessThan(Cep.of("01310100"));
        assertThatThrownBy(() -> Cep.of("1234")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Cep.of("0131010A")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void moneyRoundsHalfUpToTwoDecimals() {
        assertThat(Money.of("10.005").amount()).isEqualByComparingTo("10.01");
        assertThat(Money.of("10.004").amount()).isEqualByComparingTo("10.00");
        assertThat(Money.of("10").amount().scale()).isEqualTo(2);
    }

    @Test
    void moneyArithmetic() {
        assertThat(Money.of("10.10").plus(Money.of("0.25"))).isEqualTo(Money.of("10.35"));
        assertThat(Money.of("10.10").minus(Money.of("12.00")).isNegative()).isTrue();
        assertThat(Money.of("-3.00").abs()).isEqualTo(Money.of("3"));
        assertThat(Money.ZERO).isEqualTo(Money.of("0.00"));
    }

    @Test
    void weightConvertsTonsAndRejectsNegative() {
        assertThat(Weight.ofTons(new BigDecimal("1.2")).kg()).isEqualByComparingTo("1200");
        assertThat(Weight.ofKg("12.5").plus(Weight.ofKg("1.5")).kg()).isEqualByComparingTo("14");
        assertThat(Weight.ofKg("12.5").max(Weight.ofKg("18.0"))).isEqualTo(Weight.ofKg("18.0"));
        assertThatThrownBy(() -> Weight.ofKg("-1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tenantIdParsesUuid() {
        UUID uuid = UUID.randomUUID();
        assertThat(TenantId.of(uuid.toString()).value()).isEqualTo(uuid);
        assertThatThrownBy(() -> TenantId.of("nao-e-uuid")).isInstanceOf(IllegalArgumentException.class);
    }
}

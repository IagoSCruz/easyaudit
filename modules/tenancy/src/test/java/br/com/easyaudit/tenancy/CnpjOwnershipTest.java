package br.com.easyaudit.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import br.com.easyaudit.shared.Cnpj;
import br.com.easyaudit.tenancy.domain.CnpjEntry;
import br.com.easyaudit.tenancy.domain.CnpjOwnership;
import org.junit.jupiter.api.Test;

class CnpjOwnershipTest {

    private static CnpjEntry entry(String input) {
        return CnpjEntry.parse(input);
    }

    @Test
    void rootCoversEveryBranch() {
        var entries = List.of(entry("11.222.333"));
        assertThat(CnpjOwnership.owns(entries, Cnpj.of("11.222.333/0001-81"))).isTrue();
        // outra filial da mesma raiz (CNPJ válido com sufixo 0002)
        assertThat(CnpjOwnership.owns(entries, Cnpj.of("11.222.333/0002-62"))).isTrue();
    }

    @Test
    void fullEntryCoversOnlyThatBranch() {
        var entries = List.of(entry("11222333000181"));
        assertThat(CnpjOwnership.owns(entries, Cnpj.of("11222333000181"))).isTrue();
        assertThat(CnpjOwnership.owns(entries, Cnpj.of("11222333000262"))).isFalse();
    }

    @Test
    void alphanumericRootAndFull() {
        assertThat(CnpjOwnership.owns(List.of(entry("12ABC345")), Cnpj.of("12.ABC.345/01DE-35"))).isTrue();
        assertThat(CnpjOwnership.owns(List.of(entry("12ABC34501DE35")), Cnpj.of("12ABC345"+"01DE35"))).isTrue();
    }

    @Test
    void unrelatedRootDoesNotMatch() {
        assertThat(CnpjOwnership.owns(List.of(entry("99888777")), Cnpj.of("11222333000181"))).isFalse();
        assertThat(CnpjOwnership.owns(List.of(), Cnpj.of("11222333000181"))).isFalse();
    }
}

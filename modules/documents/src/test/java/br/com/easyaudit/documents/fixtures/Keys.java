package br.com.easyaudit.documents.fixtures;

import br.com.easyaudit.shared.AccessKey;

/** Monta chaves de acesso de 44 dígitos com dígito verificador correto. */
public final class Keys {

    private Keys() {
    }

    public static String build(String uf, String yearMonth, String cnpj, String model, int series, int number,
            String randomCode) {
        String first43 = uf + yearMonth + cnpj + model + String.format("%03d", series)
                + String.format("%09d", number) + "1" + randomCode;
        return first43 + AccessKey.checkDigit(first43);
    }
}

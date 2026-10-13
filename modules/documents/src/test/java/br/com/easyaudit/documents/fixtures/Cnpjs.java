package br.com.easyaudit.documents.fixtures;

/** Gera CNPJs numéricos válidos (com dígitos verificadores) para as fixtures. */
public final class Cnpjs {

    private static final int[] W1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
    private static final int[] W2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

    private Cnpjs() {
    }

    /** @param root 8 caracteres  @param branch 4 dígitos (ex.: "0001") */
    public static String of(String root, String branch) {
        String base = root + branch;
        int d1 = digit(base, W1);
        int d2 = digit(base + d1, W2);
        return base + d1 + d2;
    }

    private static int digit(String s, int[] weights) {
        int sum = 0;
        for (int i = 0; i < weights.length; i++) {
            sum += (s.charAt(i) - '0') * weights[i];
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }
}

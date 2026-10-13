package br.com.easyaudit.documents.fixtures;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Utilitários de montagem de XML por texto. */
final class Xml {

    private Xml() {
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String money(String value) {
        return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String qty(String value) {
        return new BigDecimal(value).setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    static String tag(String name, String value) {
        return "<" + name + ">" + esc(value) + "</" + name + ">";
    }
}

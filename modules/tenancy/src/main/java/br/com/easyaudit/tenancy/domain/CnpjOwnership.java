package br.com.easyaudit.tenancy.domain;

import java.util.Collection;

import br.com.easyaudit.shared.Cnpj;

/** Regra de posse: o tenant possui o CNPJ se tem a entrada completa idêntica ou a raiz dele. */
public final class CnpjOwnership {

    private CnpjOwnership() {
    }

    public static boolean owns(Collection<CnpjEntry> entries, Cnpj cnpj) {
        return entries.stream().anyMatch(entry -> switch (entry.kind()) {
            case FULL -> entry.value().equals(cnpj.value());
            case ROOT -> entry.value().equals(cnpj.root());
        });
    }
}

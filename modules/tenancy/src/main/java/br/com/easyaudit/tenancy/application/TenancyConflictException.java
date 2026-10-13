package br.com.easyaudit.tenancy.application;

/** Conflito de unicidade: slug duplicado ou CNPJ/raiz já pertencente a outro tenant. */
public class TenancyConflictException extends RuntimeException {

    private final String code;

    public TenancyConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}

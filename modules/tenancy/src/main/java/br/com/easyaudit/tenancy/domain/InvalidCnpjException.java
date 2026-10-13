package br.com.easyaudit.tenancy.domain;

public class InvalidCnpjException extends RuntimeException {

    public InvalidCnpjException(String message) {
        super(message);
    }
}

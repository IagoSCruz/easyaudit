package br.com.easyaudit.tenancy.application;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Chaves mestras para cifrar os segredos das credenciais (AES-256, 32 bytes em Base64), por versão.
 * Exemplo: {@code easyaudit.credentials.keys.1=<base64>}, {@code easyaudit.credentials.active-version=1}.
 * A rotação adiciona uma versão nova e mantém as antigas para decifrar segredos já gravados.
 */
@ConfigurationProperties("easyaudit.credentials")
public record CredentialsProperties(Map<Integer, String> keys, Integer activeVersion) {

    public CredentialsProperties {
        keys = keys == null ? Map.of() : keys;
        activeVersion = activeVersion == null ? 1 : activeVersion;
    }
}

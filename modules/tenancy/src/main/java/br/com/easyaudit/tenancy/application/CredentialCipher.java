package br.com.easyaudit.tenancy.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * AES-256-GCM com IV aleatório de 12 bytes: {@code IV || ciphertext+tag}. O {@code keyId} entra como dado
 * autenticado adicional, então um segredo cifrado não pode ser trocado de uma credencial para outra.
 */
@Component
public class CredentialCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, SecretKey> keys = new HashMap<>();
    private final int activeVersion;

    public CredentialCipher(CredentialsProperties properties) {
        properties.keys().forEach((version, base64) -> {
            byte[] raw = Base64.getDecoder().decode(base64.trim());
            if (raw.length != 32) {
                throw new IllegalStateException(
                        "A chave easyaudit.credentials.keys." + version + " deve ter 32 bytes (AES-256) em Base64.");
            }
            keys.put(version, new SecretKeySpec(raw, "AES"));
        });
        if (!keys.containsKey(properties.activeVersion())) {
            throw new IllegalStateException("Defina easyaudit.credentials.keys." + properties.activeVersion()
                    + " (variável EASYAUDIT_CREDENTIALS_KEY) para cifrar os segredos das credenciais.");
        }
        this.activeVersion = properties.activeVersion();
    }

    public int activeVersion() {
        return activeVersion;
    }

    public byte[] encrypt(String keyId, String secret) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keys.get(activeVersion), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao cifrar o segredo", e);
        }
    }

    public String decrypt(String keyId, int keyVersion, byte[] stored) {
        SecretKey key = keys.get(keyVersion);
        if (key == null) {
            throw new IllegalStateException("Chave mestra da versão " + keyVersion + " não configurada.");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            cipher.updateAAD(keyId.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao decifrar o segredo", e);
        }
    }
}

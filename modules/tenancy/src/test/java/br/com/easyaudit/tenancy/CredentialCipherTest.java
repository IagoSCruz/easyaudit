package br.com.easyaudit.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.Map;

import br.com.easyaudit.tenancy.application.CredentialCipher;
import br.com.easyaudit.tenancy.application.CredentialsProperties;
import org.junit.jupiter.api.Test;

class CredentialCipherTest {

    private static String key(int fill) {
        byte[] raw = new byte[32];
        java.util.Arrays.fill(raw, (byte) fill);
        return Base64.getEncoder().encodeToString(raw);
    }

    private final CredentialCipher cipher = new CredentialCipher(new CredentialsProperties(Map.of(1, key(1)), 1));

    @Test
    void roundTrips() {
        byte[] stored = cipher.encrypt("ea_abc", "segredo-super-secreto");
        assertThat(new String(stored)).doesNotContain("segredo");
        assertThat(cipher.decrypt("ea_abc", 1, stored)).isEqualTo("segredo-super-secreto");
    }

    @Test
    void ivIsRandomSoCiphertextsDiffer() {
        assertThat(cipher.encrypt("ea_abc", "x")).isNotEqualTo(cipher.encrypt("ea_abc", "x"));
    }

    @Test
    void secretIsBoundToItsKeyId() {
        byte[] stored = cipher.encrypt("ea_abc", "x");
        assertThatThrownBy(() -> cipher.decrypt("ea_outra", 1, stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertextIsRejected() {
        byte[] stored = cipher.encrypt("ea_abc", "x");
        stored[stored.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt("ea_abc", 1, stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rotationKeepsDecryptingOldVersions() {
        byte[] old = cipher.encrypt("ea_abc", "antigo");
        var rotated = new CredentialCipher(new CredentialsProperties(Map.of(1, key(1), 2, key(2)), 2));
        assertThat(rotated.activeVersion()).isEqualTo(2);
        assertThat(rotated.decrypt("ea_abc", 1, old)).isEqualTo("antigo");
        byte[] fresh = rotated.encrypt("ea_abc", "novo");
        assertThat(rotated.decrypt("ea_abc", 2, fresh)).isEqualTo("novo");
        assertThatThrownBy(() -> cipher.decrypt("ea_abc", 2, fresh)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsFastWithoutTheActiveKeyOrWithAWrongSize() {
        assertThatThrownBy(() -> new CredentialCipher(new CredentialsProperties(Map.of(), 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EASYAUDIT_CREDENTIALS_KEY");
        assertThatThrownBy(() -> new CredentialCipher(new CredentialsProperties(
                Map.of(1, Base64.getEncoder().encodeToString(new byte[16])), 1)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}

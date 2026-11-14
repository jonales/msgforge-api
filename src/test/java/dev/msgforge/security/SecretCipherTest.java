package dev.msgforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY = "chave-de-teste-com-pelo-menos-32-caracteres";

    @Test
    void roundTripsAndNeverRepeatsTheCiphertext() {
        var cipher = new SecretCipher(KEY);
        var owner = UUID.randomUUID();

        var first = cipher.encrypt(owner, "s3nh@:secreta");
        var second = cipher.encrypt(owner, "s3nh@:secreta");

        assertThat(first).startsWith("v1:").doesNotContain("s3nh").isNotEqualTo(second);
        assertThat(cipher.decrypt(owner, first)).isEqualTo("s3nh@:secreta");
        assertThat(new SecretCipher(KEY).decrypt(owner, second)).isEqualTo("s3nh@:secreta");
    }

    @Test
    void ciphertextOfOneInstanceDoesNotOpenInAnotherNorWithAnotherKey() {
        var cipher = new SecretCipher(KEY);
        var encrypted = cipher.encrypt(UUID.randomUUID(), "valor-sigiloso");

        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), encrypted))
                .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("valor-sigiloso");
        assertThatThrownBy(() -> new SecretCipher(KEY + "x").decrypt(UUID.randomUUID(), encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void withoutKeyItIsDisabledAndAShortKeyFailsTheBoot() {
        assertThat(new SecretCipher("").enabled()).isFalse();
        assertThat(new SecretCipher(null).enabled()).isFalse();
        assertThatThrownBy(() -> new SecretCipher("").encrypt(UUID.randomUUID(), "x"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher("curta")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SECRETS_ENCRYPTION_KEY");
    }
}

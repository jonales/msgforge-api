package dev.msgforge.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.msgforge.notification.EmailAddresses.InvalidEmailException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmailAddressesTest {

    @ParameterizedTest
    @ValueSource(strings = {"ana@exemplo.com", "ana.souza+avisos@sub.exemplo.com.br", "  ana@exemplo.com\t"})
    void acceptsPlainAddresses(String email) {
        assertThat(EmailAddresses.normalize(email)).isEqualTo(email.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "ana", "@exemplo.com", "ana@", "Ana <ana@exemplo.com>",
            "ana@exemplo.com, bia@exemplo.com", "ana@exemplo.com\r\nBcc: bia@exemplo.com", "ana @exemplo.com"})
    void rejectsAnythingButOneBareAddress(String email) {
        assertThatThrownBy(() -> EmailAddresses.normalize(email)).isInstanceOf(InvalidEmailException.class);
    }

    @org.junit.jupiter.api.Test
    void rejectsAddressesLongerThanSmtpAllows() {
        var local = "a".repeat(64);
        var domain = "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".com";
        assertThatThrownBy(() -> EmailAddresses.normalize(local + "@" + domain))
                .isInstanceOf(InvalidEmailException.class);
    }
}

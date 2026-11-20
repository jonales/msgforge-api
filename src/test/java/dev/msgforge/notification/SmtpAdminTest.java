// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.msgforge.audit.AuditLog;
import dev.msgforge.common.ApiExceptionHandler.InvalidFieldException;
import dev.msgforge.security.SecretCipher;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Regras do formulário do SMTP e o limite de testes de conexão (ADR 0019). */
class SmtpAdminTest {

    private static SmtpAdmin.Form form(String host, String port, String user, String password, String from) {
        return new SmtpAdmin.Form(host, port, user, password, from, true, "");
    }

    private static void rejected(SmtpAdmin.Form form, String field, String reason) {
        assertThatThrownBy(() -> SmtpAdmin.validate(form)).isInstanceOfSatisfying(InvalidFieldException.class, e -> {
            assertThat(e.field()).isEqualTo(field);
            assertThat(e.reason()).isEqualTo(reason);
        });
    }

    @Test
    void aValidFormBecomesTheConfiguration() {
        var config = SmtpAdmin.validate(form(" smtp.exemplo.com ", "", "avisos@exemplo.com", "s3nh@",
                "Msgforge <avisos@exemplo.com>"));
        assertThat(config.host()).isEqualTo("smtp.exemplo.com");
        assertThat(config.port()).isEqualTo(587);
        assertThat(config.timeoutMs()).isEqualTo(10000);
        assertThat(config.authenticated()).isTrue();
        assertThat(SmtpAdmin.validate(form("[::1]", "25", "", "", "")).password()).isNull();
    }

    @Test
    void invalidFieldsAreRejectedWithTheReason() {
        rejected(form("", "587", null, null, null), "host", "required");
        rejected(form("smtp exemplo.com", "587", null, null, null), "host", "invalid");
        rejected(form("smtp.exemplo.com\r\nRCPT", "587", null, null, null), "host", "invalid");
        rejected(form("smtp.exemplo.com", "70000", null, null, null), "port", "invalid");
        rejected(form("smtp.exemplo.com", "abc", null, null, null), "port", "invalid");
        rejected(form("smtp.exemplo.com", "587", "a\nb", null, null), "username", "invalid");
        rejected(form("smtp.exemplo.com", "587", "a", "linha\r\nnova", null), "password", "invalid");
        rejected(form("smtp.exemplo.com", "587", null, null, "a@b.com, c@d.com"), "from", "invalid");
        rejected(new SmtpAdmin.Form("smtp.exemplo.com", "587", null, null, null, true, "50"), "timeoutMs", "invalid");
    }

    @Test
    void connectionTestsAreLimitedPerMinute() {
        var store = mock(SmtpSettingsStore.class);
        when(store.find()).thenReturn(Optional.empty());
        var sender = mock(EmailSender.class);
        when(sender.test(any())).thenReturn(new EmailSender.TestResult(false, "recusado"));
        var now = new AtomicReference<>(Instant.parse("2026-11-20T10:00:00Z"));
        var clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        var admin = new SmtpAdmin(store, sender, new SecretCipher(""), mock(AuditLog.class), clock);
        var valid = form("127.0.0.1", "2525", null, null, null);

        for (int i = 0; i < SmtpAdmin.TESTS_PER_MINUTE; i++) {
            assertThat(admin.test(valid).ok()).isFalse();
        }
        assertThatThrownBy(() -> admin.test(valid)).isInstanceOfSatisfying(InvalidFieldException.class,
                e -> assertThat(e.reason()).isEqualTo("tooMany"));

        now.set(now.get().plusSeconds(61));
        assertThat(admin.test(valid).reason()).isEqualTo("recusado");
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.notification;

import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.audit.AuditAction;
import dev.msgforge.audit.AuditEvent;
import dev.msgforge.audit.AuditLog;
import dev.msgforge.audit.AuditOutcome;
import dev.msgforge.common.ApiExceptionHandler.InvalidFieldException;
import dev.msgforge.security.SecretCipher;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * O SMTP pelo Manager (ADR 0019): valida, cifra a senha, grava, audita e remonta o envio; e o teste de
 * conexão antes de salvar. Quem pode (admin da plataforma) é conferido por quem chama.
 *
 * <p>Erros de campo saem como {@link InvalidFieldException} ({@code field} + {@code reason}), que a tela
 * traduz para {@code manager.server.smtp.error.<field>.<reason>}.
 */
@Service
public class SmtpAdmin {

    /** Testes de conexão por minuto, para a tela não virar varredura de rede. */
    static final int TESTS_PER_MINUTE = 10;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.\\-]{1,253}|\\[[0-9A-Fa-f:.]{2,45}\\]");

    /**
     * O que veio do formulário. {@code password} em branco = mantém a guardada (ou nenhuma, se não há).
     */
    public record Form(String host, String port, String username, String password, String from, boolean starttls,
                       String timeoutMs) {

        /** Nunca a senha. */
        @Override
        public String toString() {
            return "SmtpForm[" + host + ":" + port + "]";
        }
    }

    private final SmtpSettingsStore store;
    private final EmailSender sender;
    private final SecretCipher cipher;
    private final AuditLog audit;
    private final Clock clock;
    private final Deque<Instant> tests = new ArrayDeque<>();

    @Autowired
    public SmtpAdmin(SmtpSettingsStore store, EmailSender sender, SecretCipher cipher, AuditLog audit) {
        this(store, sender, cipher, audit, Clock.systemUTC());
    }

    SmtpAdmin(SmtpSettingsStore store, EmailSender sender, SecretCipher cipher, AuditLog audit, Clock clock) {
        this.store = store;
        this.sender = sender;
        this.cipher = cipher;
        this.audit = audit;
        this.clock = clock;
    }

    /** Valida, cifra a senha nova (ou mantém a guardada), grava, audita e passa a valer na hora. */
    public void save(ManagerPrincipal who, Form form) {
        var config = validate(form);
        var existing = store.find().orElse(null);
        String encrypted;
        if (config.password() != null) {
            if (!cipher.enabled()) {
                throw new InvalidFieldException("password", "noKey", "SECRETS_ENCRYPTION_KEY is not configured");
            }
            encrypted = cipher.encrypt(EmailSender.SMTP_OWNER, config.password());
        } else {
            // Sem usuário não há login: a senha guardada sai junto
            encrypted = config.authenticated() && existing != null ? existing.passwordEncrypted() : null;
        }
        if (config.authenticated() && encrypted == null) {
            throw new InvalidFieldException("password", "required", "password is required with a username");
        }
        store.save(new SmtpSettingsStore.Row(config.host(), config.port(), config.username(), encrypted, config.from(),
                config.starttls(), config.timeoutMs(), null, label(who)));
        audit.record(event(who, AuditAction.SERVER_SMTP_UPDATED, config.host() + ":" + config.port()));
        sender.reload();
    }

    /** Apaga a configuração do Manager: volta a valer a do ambiente (SMTP_*), se houver. */
    public boolean remove(ManagerPrincipal who) {
        var existing = store.find();
        if (!store.delete()) {
            return false;
        }
        audit.record(event(who, AuditAction.SERVER_SMTP_REMOVED,
                existing.map(r -> r.host() + ":" + r.port()).orElse(null)));
        sender.reload();
        return true;
    }

    /**
     * Conecta e faz o login com o que está no formulário, sem salvar nem enviar. Senha em branco = a guardada.
     *
     * @throws InvalidFieldException com {@code field = "test"} e {@code reason = "tooMany"} acima do limite
     */
    public EmailSender.TestResult test(Form form) {
        var config = validate(form);
        if (!allowTest()) {
            throw new InvalidFieldException("test", "tooMany", "too many SMTP tests, wait a minute");
        }
        if (config.authenticated() && config.password() == null) {
            var stored = store.find().map(SmtpSettingsStore.Row::passwordEncrypted).orElse(null);
            if (stored != null && cipher.enabled()) {
                try {
                    config = withPassword(config, cipher.decrypt(EmailSender.SMTP_OWNER, stored));
                } catch (RuntimeException e) {
                    return new EmailSender.TestResult(false, "a senha guardada não pôde ser decifrada");
                }
            }
        }
        return sender.test(config);
    }

    private synchronized boolean allowTest() {
        var now = clock.instant();
        while (!tests.isEmpty() && Duration.between(tests.peekFirst(), now).compareTo(Duration.ofMinutes(1)) >= 0) {
            tests.pollFirst();
        }
        if (tests.size() >= TESTS_PER_MINUTE) {
            return false;
        }
        tests.addLast(now);
        return true;
    }

    /** As regras do formulário; a senha em branco vira null (mantém a guardada). */
    static SmtpProperties validate(Form form) {
        var host = strip(form.host());
        if (host == null) {
            throw new InvalidFieldException("host", "required", "host is required");
        }
        if (!HOST.matcher(host).matches()) {
            throw new InvalidFieldException("host", "invalid", "host must be a name or an IP address");
        }
        var port = number(form.port(), 587, "port");
        if (port < 1 || port > 65535) {
            throw new InvalidFieldException("port", "invalid", "port must be between 1 and 65535");
        }
        var timeout = number(form.timeoutMs(), 10000, "timeoutMs");
        if (timeout < 1000 || timeout > 120000) {
            throw new InvalidFieldException("timeoutMs", "invalid", "timeout must be between 1000 and 120000 ms");
        }
        var username = strip(form.username());
        if (username != null && (username.length() > 254 || username.chars().anyMatch(Character::isISOControl))) {
            throw new InvalidFieldException("username", "invalid", "invalid username");
        }
        var password = form.password() == null || form.password().isEmpty() ? null : form.password();
        if (password != null && (password.length() > 500 || password.chars().anyMatch(c -> c == '\r' || c == '\n'))) {
            throw new InvalidFieldException("password", "invalid", "invalid password");
        }
        var from = strip(form.from());
        if (from != null) {
            if (from.length() > 320 || from.chars().anyMatch(Character::isISOControl)) {
                throw new InvalidFieldException("from", "invalid", "invalid sender");
            }
            try {
                var parsed = InternetAddress.parse(from, true);
                if (parsed.length != 1) {
                    throw new InvalidFieldException("from", "invalid", "the sender must be one address");
                }
            } catch (AddressException e) {
                throw new InvalidFieldException("from", "invalid", "invalid sender");
            }
        }
        return new SmtpProperties(host, port, username, password, from, form.starttls(), timeout);
    }

    private static int number(String raw, int fallback, String field) {
        var value = strip(raw);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new InvalidFieldException(field, "invalid", field + " must be a number");
        }
    }

    private static SmtpProperties withPassword(SmtpProperties p, String password) {
        return new SmtpProperties(p.host(), p.port(), p.username(), password, p.from(), p.starttls(), p.timeoutMs());
    }

    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String label(ManagerPrincipal who) {
        return who == null || who.globalKey() ? AuditLog.GLOBAL_KEY : who.email();
    }

    private static AuditEvent event(ManagerPrincipal who, AuditAction action, String target) {
        return new AuditEvent(action, AuditOutcome.OK, null, who == null ? null : who.userId(), label(who), target,
                null, null);
    }
}

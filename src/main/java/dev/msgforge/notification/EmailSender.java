// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.notification;

import dev.msgforge.event.WebhookSender.Attempt;
import dev.msgforge.security.SecretCipher;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Entrega de um {@link EmailMessage} pelo SMTP ({@link JavaMailSender}), uma tentativa por vez: quem
 * agenda as novas tentativas é a outbox (ou a fila em memória, com OUTBOX_ENABLED=false).
 *
 * <p>O {@code JavaMailSender} é montado aqui, e não pelo auto-config do Spring ({@code spring.mail.*}),
 * para que "sem SMTP_HOST" signifique e-mail desligado, sem bean pela metade.
 */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);
    /** Dono fixo da senha do SMTP no {@link SecretCipher} (o dado associado do AES-GCM). */
    static final UUID SMTP_OWNER = UUID.nameUUIDFromBytes("msgforge:smtp".getBytes(StandardCharsets.UTF_8));

    /** De onde vem a configuração em uso (ADR 0019): a do Manager vence a do ambiente. */
    public enum Source { DATABASE, ENVIRONMENT, NONE }

    /**
     * A configuração em uso e o envio montado com ela; trocada inteira quando a configuração muda.
     *
     * @param problem por que a configuração do banco não pode ser usada (senha que não decifra), ou null
     */
    private record Snapshot(SmtpProperties config, JavaMailSender mail, Source source, Instant version,
                            String updatedBy, String problem) {}

    private final SmtpProperties environment;
    private final SmtpSettingsStore store;
    private final SecretCipher cipher;
    private volatile Snapshot current;

    /** Só o ambiente (testes e quem não usa o Manager para o SMTP). */
    public EmailSender(SmtpProperties properties) {
        this(properties, null, null);
    }

    @Autowired
    public EmailSender(SmtpProperties properties, SmtpSettingsStore store, SecretCipher cipher) {
        this.environment = properties;
        this.store = store;
        this.cipher = cipher;
        reload();
    }

    /** Relê a configuração (banco, senão ambiente) e remonta o envio. Chamado ao salvar pelo Manager. */
    public synchronized void reload() {
        try {
            current = resolve();
        } catch (RuntimeException e) {
            // Banco fora do ar ao subir: fica o ambiente; a verificação de minuto em minuto tenta de novo
            log.warn("Configuração do SMTP no banco ilegível ({}); usando a do ambiente", e.getClass().getSimpleName());
            current = fromEnvironment();
        }
    }

    /** Em mais de um nó, quem não salvou percebe a mudança pela data dela (ADR 0019). */
    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    void refreshIfChanged() {
        if (store == null) {
            return;
        }
        try {
            if (!Objects.equals(store.lastChange().orElse(null), current.version())) {
                reload();
            }
        } catch (RuntimeException e) {
            log.debug("Não conferiu a configuração do SMTP: {}", e.getMessage());
        }
    }

    private Snapshot resolve() {
        var row = store == null ? null : store.find().orElse(null);
        if (row == null) {
            return fromEnvironment();
        }
        String password = null;
        String problem = null;
        if (row.passwordEncrypted() != null) {
            try {
                password = cipher.decrypt(SMTP_OWNER, row.passwordEncrypted());
            } catch (RuntimeException e) {
                problem = "a senha guardada não pôde ser decifrada (SECRETS_ENCRYPTION_KEY ausente ou trocada)";
            }
        }
        var config = new SmtpProperties(row.host(), row.port(), row.username(), password, row.from(), row.starttls(),
                row.timeoutMs());
        return new Snapshot(config, problem == null ? build(config) : null, Source.DATABASE, row.updatedAt(),
                row.updatedBy(), problem);
    }

    private Snapshot fromEnvironment() {
        return new Snapshot(environment, environment.enabled() ? build(environment) : null,
                environment.enabled() ? Source.ENVIRONMENT : Source.NONE, null, null, null);
    }

    /** O SMTP está configurado (Manager ou SMTP_HOST)? Sem ele, nenhuma notificação por e-mail sai. */
    public boolean isEnabled() {
        return current.mail() != null;
    }

    /** Estado do SMTP para o Manager; nunca leva a senha. */
    public record Status(boolean enabled, String host, int port, boolean starttls, boolean authenticated,
                         String from, Source source, Instant updatedAt, String updatedBy, String problem,
                         String username, int timeoutMs) {}

    public Status status() {
        var s = current;
        var p = s.config();
        return new Status(s.mail() != null, blankToNull(p.host()), p.port(), p.starttls(), p.authenticated(),
                sender(p), s.source(), s.version(), s.updatedBy(), s.problem(), blankToNull(p.username()), p.timeoutMs());
    }

    /** Resultado de um teste de conexão: {@code reason} sem a senha. */
    public record TestResult(boolean ok, String reason) {}

    /** Conecta e faz o login com {@code config}, sem enviar nada (o "Testar" do Manager). */
    public TestResult test(SmtpProperties config) {
        try {
            ((JavaMailSenderImpl) build(config)).testConnection();
            return new TestResult(true, null);
        } catch (MessagingException | RuntimeException e) {
            return new TestResult(false, withoutSecret(reason(e), config.password()));
        }
    }

    private static String withoutSecret(String text, String secret) {
        return secret == null || secret.isEmpty() || text == null ? text : text.replace(secret, "***");
    }

    /** Uma tentativa de envio, multipart/alternative (texto e HTML). */
    public Attempt attempt(EmailMessage email) {
        var s = current;
        var mail = s.mail();
        if (mail == null) {
            return new Attempt.Failed(s.problem() != null ? "SMTP: " + s.problem() : "SMTP desligado (sem configuração)");
        }
        try {
            var message = mail.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            var from = sender(s.config());
            if (from != null) {
                helper.setFrom(from);
            }
            helper.setTo(email.to());
            helper.setSubject(email.subject());
            helper.setText(email.text(), email.html());
            mail.send(message);
            return new Attempt.Delivered();
        } catch (MessagingException | MailParseException | MailPreparationException e) {
            // Endereço ou conteúdo inválido: repetir não muda nada
            return new Attempt.Failed(reason(e));
        } catch (MailSendException e) {
            return rejectedRecipient(e) ? new Attempt.Failed(reason(e)) : new Attempt.Retry(reason(e));
        } catch (MailAuthenticationException e) {
            // Credencial errada: tenta de novo (pode ser corrigida), e vira MORTO no fim das tentativas
            return new Attempt.Retry(reason(e));
        } catch (MailException e) {
            return new Attempt.Retry(reason(e));
        }
    }

    /** O servidor recusou o destinatário (5xx no RCPT): não adianta repetir. */
    private static boolean rejectedRecipient(MailSendException e) {
        for (var failure : e.getFailedMessages().values()) {
            if (failure instanceof SendFailedException sfe && sfe.getInvalidAddresses() != null
                    && sfe.getInvalidAddresses().length > 0) {
                return true;
            }
        }
        return false;
    }

    private static String sender(SmtpProperties p) {
        var from = blankToNull(p.from());
        return from != null ? from : blankToNull(p.username());
    }

    private static JavaMailSender build(SmtpProperties p) {
        var sender = new JavaMailSenderImpl();
        sender.setHost(p.host().trim());
        sender.setPort(p.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        if (p.authenticated()) {
            sender.setUsername(p.username());
            sender.setPassword(p.password());
        }
        var props = new Properties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(p.authenticated()));
        props.put("mail.smtp.starttls.enable", String.valueOf(p.starttls()));
        props.put("mail.smtp.starttls.required", String.valueOf(p.starttls()));
        var timeout = String.valueOf(Math.max(1000, p.timeoutMs()));
        props.put("mail.smtp.connectiontimeout", timeout);
        props.put("mail.smtp.timeout", timeout);
        props.put("mail.smtp.writetimeout", timeout);
        sender.setJavaMailProperties(props);
        return sender;
    }

    private static String reason(Exception e) {
        var message = e.getMessage();
        var cause = e.getCause();
        while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause != null && cause.getMessage() != null && (message == null || !message.contains(cause.getMessage()))) {
            message = (message == null ? "" : message + ": ") + cause.getMessage();
        }
        // Uma linha só (vai para o last_error da outbox e para o log)
        return (e.getClass().getSimpleName() + (message == null ? "" : ": " + message)).replaceAll("\\s+", " ");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

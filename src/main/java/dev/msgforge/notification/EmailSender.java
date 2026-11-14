package dev.msgforge.notification;

import dev.msgforge.event.WebhookSender.Attempt;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
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

    private final SmtpProperties properties;
    private final JavaMailSender mail;

    public EmailSender(SmtpProperties properties) {
        this.properties = properties;
        this.mail = properties.enabled() ? build(properties) : null;
    }

    /** O SMTP está configurado (SMTP_HOST)? Sem ele, nenhuma notificação por e-mail sai. */
    public boolean isEnabled() {
        return mail != null;
    }

    /** Estado do SMTP para o Manager; nunca leva a senha. */
    public record Status(boolean enabled, String host, int port, boolean starttls, boolean authenticated,
                         String from) {}

    public Status status() {
        return new Status(isEnabled(), blankToNull(properties.host()), properties.port(), properties.starttls(),
                properties.authenticated(), sender());
    }

    /** Uma tentativa de envio, multipart/alternative (texto e HTML). */
    public Attempt attempt(EmailMessage email) {
        if (mail == null) {
            return new Attempt.Failed("SMTP desligado (sem SMTP_HOST)");
        }
        try {
            var message = mail.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            var from = sender();
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

    private String sender() {
        var from = blankToNull(properties.from());
        return from != null ? from : blankToNull(properties.username());
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

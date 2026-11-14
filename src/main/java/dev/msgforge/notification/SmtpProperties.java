package dev.msgforge.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Servidor SMTP das notificações por e-mail (ADR 0011). Sem {@code host}, o e-mail fica desligado
 * e a aplicação sobe normalmente.
 *
 * @param host       SMTP_HOST; vazio desliga o e-mail
 * @param port       SMTP_PORT
 * @param username   SMTP_USERNAME; vazio = sem autenticação
 * @param password   SMTP_PASSWORD; só sai daqui para o {@code JavaMailSender}, nunca para a outbox
 * @param from       SMTP_FROM, o remetente (ex.: {@code Msgforge <avisos@exemplo.com>})
 * @param starttls   SMTP_STARTTLS: exige STARTTLS antes de autenticar
 * @param timeoutMs  SMTP_TIMEOUT_MS: conexão, leitura e escrita no servidor SMTP
 */
@ConfigurationProperties(prefix = "msgforge.smtp")
public record SmtpProperties(
        String host,
        @DefaultValue("587") int port,
        String username,
        String password,
        String from,
        @DefaultValue("true") boolean starttls,
        @DefaultValue("10000") int timeoutMs) {

    public boolean enabled() {
        return host != null && !host.isBlank();
    }

    public boolean authenticated() {
        return username != null && !username.isBlank();
    }
}

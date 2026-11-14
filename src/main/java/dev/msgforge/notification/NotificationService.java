package dev.msgforge.notification;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Ponto único para notificar alguém (ADR 0011): quem notifica diz o quê e para quem; o serviço
 * entrega por todos os canais ligados ({@link NotificationChannel}). Nada sai na thread de quem
 * chamou: cada canal só enfileira (outbox, ADR 0012).
 *
 * <p>Chamado dentro de uma transação (um convite criado junto com o usuário, por exemplo), a
 * notificação só entra na fila depois do commit; num rollback, nada é enviado. Fora de transação,
 * entra na hora, e uma falha ao enfileirar chega a quem chamou.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final List<NotificationChannel> channels;
    private final EmailSender email;

    public NotificationService(List<NotificationChannel> channels, EmailSender email) {
        this.channels = List.copyOf(channels);
        this.email = email;
    }

    public enum Result {
        /** Entrou na fila de pelo menos um canal (ou entra depois do commit). */
        QUEUED,
        /** Nenhum canal ligado: com o SMTP sem configurar, nada é enviado. */
        DISABLED
    }

    /**
     * O e-mail está ligado (SMTP_HOST)? Sem ele, telas como "esqueci a senha" devem orientar a pessoa
     * a falar com o gestor, em vez de prometer um e-mail que não sai.
     */
    public boolean isEmailEnabled() {
        return email.isEnabled();
    }

    /** Estado do SMTP para a tela Servidor do Manager (sem a senha). */
    public EmailSender.Status emailStatus() {
        return email.status();
    }

    public Result send(Notification notification) {
        var enabled = channels.stream().filter(NotificationChannel::isEnabled).toList();
        if (enabled.isEmpty()) {
            log.debug("Notificação {} não enviada: nenhum canal ligado", notification.kind());
            return Result.DISABLED;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitQuietly(enabled, notification);
                }
            });
        } else {
            enabled.forEach(channel -> channel.submit(notification));
        }
        return Result.QUEUED;
    }

    private static void submitQuietly(List<NotificationChannel> channels, Notification notification) {
        for (var channel : channels) {
            try {
                channel.submit(notification);
            } catch (RuntimeException e) {
                log.error("Notificação {} perdida no canal {}: {}", notification.kind(), channel.name(),
                        e.getMessage(), e);
            }
        }
    }
}

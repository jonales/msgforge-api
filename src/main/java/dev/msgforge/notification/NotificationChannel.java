package dev.msgforge.notification;

/**
 * Um meio de entregar notificações (ADR 0011). O primeiro é o e-mail ({@link EmailChannel}); um
 * webhook de alerta ou uma mensagem de WhatsApp por uma instância de sistema entram como outros
 * canais, sem mudar quem notifica ({@link NotificationService}).
 */
public interface NotificationChannel {

    /** Nome curto do canal, para log ({@code email}). */
    String name();

    /** O canal está configurado neste servidor? Desligado, o {@link NotificationService} o pula. */
    boolean isEnabled();

    /**
     * Põe a notificação na fila de entrega (a outbox, ADR 0012). Nunca entrega na thread de quem
     * chamou: uma falha do destino não pode derrubar a ação do usuário.
     *
     * @throws RuntimeException se não conseguiu enfileirar (o banco fora do ar, por exemplo)
     */
    void submit(Notification notification);
}

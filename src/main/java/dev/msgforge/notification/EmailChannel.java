package dev.msgforge.notification;

import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.event.OrderedExecutor;
import dev.msgforge.event.WebhookSender;
import dev.msgforge.event.WebhookSender.Attempt;
import dev.msgforge.event.outbox.Outbox;
import dev.msgforge.event.outbox.OutboxProperties;
import dev.msgforge.event.outbox.OutboxWorker;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canal de e-mail: monta a mensagem ({@link EmailRenderer}) e grava na outbox como {@code EMAIL},
 * com o destinatário como chave de ordem (ADR 0012). O {@link OutboxWorker} entrega pelo
 * {@link EmailSender}, com o retry e o {@code MORTO} dos outros tipos.
 *
 * <p>Com OUTBOX_ENABLED=false, a entrega vai para uma fila em memória por destinatário, com a mesma
 * política de retry (WEBHOOK_RETRY_*), como a dos eventos (ADR 0006): se o processo cair, o e-mail
 * pendente se perde.
 */
@Component
public class EmailChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(EmailChannel.class);

    private final EmailSender sender;
    private final EmailRenderer renderer;
    private final Outbox outbox;
    private final OutboxProperties outboxProperties;
    private final ObjectProvider<OutboxWorker> worker;
    private final WebhookSender retryPolicy;
    private final JsonMapper json;
    private final OrderedExecutor memory;

    public EmailChannel(EmailSender sender, EmailRenderer renderer, Outbox outbox, OutboxProperties outboxProperties,
                        ObjectProvider<OutboxWorker> worker, WebhookSender retryPolicy, JsonMapper json,
                        MsgforgeProperties properties) {
        this.sender = sender;
        this.renderer = renderer;
        this.outbox = outbox;
        this.outboxProperties = outboxProperties;
        this.worker = worker;
        this.retryPolicy = retryPolicy;
        this.json = json;
        this.memory = new OrderedExecutor(Executors.newVirtualThreadPerTaskExecutor(),
                properties.webhook().maxPendingPerInstance());
    }

    @Override
    public String name() {
        return "email";
    }

    @Override
    public boolean isEnabled() {
        return sender.isEnabled();
    }

    @Override
    public void submit(Notification notification) {
        var email = renderer.render(notification);
        var key = orderKey(email.to());
        var event = notification.kind().event();
        if (outboxProperties.enabled()) {
            // A outbox guarda o e-mail montado; a credencial do SMTP fica só na configuração
            outbox.insert(List.of(new Outbox.NewEntry(Outbox.Type.EMAIL, key, event, email.to(), Map.of(), false,
                    json.writeValueAsString(email))));
            worker.ifAvailable(OutboxWorker::wake);
        } else if (!memory.submit(key, () -> deliverInMemory(email, event))) {
            throw new IllegalStateException("fila de e-mail cheia para o destinatário");
        }
    }

    /** E-mails para o mesmo endereço saem na ordem em que entraram, sem diferenciar maiúsculas. */
    static String orderKey(String address) {
        return address.toLowerCase(Locale.ROOT);
    }

    private void deliverInMemory(EmailMessage email, String event) {
        var attempts = retryPolicy.maxAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            var result = sender.attempt(email);
            switch (result) {
                case Attempt.Delivered _ -> {
                    return;
                }
                case Attempt.Failed f -> {
                    log.warn("E-mail {} recusado: {} (sem retry)", event, f.reason());
                    return;
                }
                case Attempt.Retry r -> {
                    if (attempt == attempts) {
                        log.warn("E-mail {} falhou após {} tentativa(s): {}", event, attempts, r.reason());
                        return;
                    }
                    log.debug("E-mail {} falhou (tentativa {}/{}): {}", event, attempt, attempts, r.reason());
                }
            }
            try {
                Thread.sleep(retryPolicy.retryDelayMs(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}

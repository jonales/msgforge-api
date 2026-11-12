package dev.msgforge.history;

import dev.msgforge.event.GatewayEvent.MessageReceived;
import dev.msgforge.event.GatewayEvent.MessageStatusUpdated;
import dev.msgforge.gateway.WhatsAppGateway.SentMessage;
import dev.msgforge.instance.Instance;
import dev.msgforge.instance.InstanceRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Grava no histórico o que entra (MESSAGES_UPSERT), o que sai (resposta dos envios) e os status
 * (MESSAGES_UPDATE). Falha ao gravar só vai para o log: o histórico nunca derruba um envio nem a
 * entrega dos eventos.
 */
@Component
public class HistoryRecorder {

    private static final Logger log = LoggerFactory.getLogger(HistoryRecorder.class);

    private final MessageHistory history;
    private final InstanceRepository instances;
    private final HistoryProperties properties;

    public HistoryRecorder(MessageHistory history, InstanceRepository instances, HistoryProperties properties) {
        this.history = history;
        this.instances = instances;
        this.properties = properties;
    }

    public void recordSent(Instance instance, SentMessage sent, String messageType, Map<String, Object> message) {
        if (!properties.enabled()) {
            return;
        }
        guarded(instance.getName(), () -> history.save(instance.getId(),
                new MessageHistory.Key(sent.remoteJid(), true, sent.id(), null), instance.getProfileName(),
                messageType, message, "PENDING", sent.timestamp()));
    }

    @EventListener
    public void on(MessageReceived event) {
        if (!properties.enabled()) {
            return;
        }
        var data = event.data();
        if (!(data.get("key") instanceof Map<?, ?> key) || !(data.get("message") instanceof Map<?, ?> message)) {
            return;
        }
        guarded(event.instanceName(), () -> instanceId(event.instanceName()).ifPresent(id -> history.save(id,
                new MessageHistory.Key(text(key.get("remoteJid")), Boolean.TRUE.equals(key.get("fromMe")),
                        text(key.get("id")), text(key.get("participant"))),
                text(data.get("pushName")), String.valueOf(data.getOrDefault("messageType", "unknown")),
                stringKeys(message), text(data.get("status")), timestamp(data.get("messageTimestamp")))));
    }

    @EventListener
    public void on(MessageStatusUpdated event) {
        if (!properties.enabled()) {
            return;
        }
        var data = event.data();
        guarded(event.instanceName(), () -> instanceId(event.instanceName()).ifPresent(id -> history.updateStatus(id,
                text(data.get("remoteJid")), text(data.get("keyId")), Boolean.TRUE.equals(data.get("fromMe")),
                text(data.get("status")))));
    }

    private java.util.Optional<UUID> instanceId(String name) {
        return instances.findByName(name).map(Instance::getId);
    }

    private static void guarded(String instanceName, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Histórico: não gravou mensagem da instância {}: {}", instanceName, e.getMessage());
        }
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static long timestamp(Object value) {
        return value instanceof Number n ? n.longValue() : java.time.Instant.now().getEpochSecond();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }
}

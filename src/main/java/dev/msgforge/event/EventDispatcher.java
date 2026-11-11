package dev.msgforge.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.event.DeliveryTarget.WebhookTarget;
import dev.msgforge.event.GatewayEvent.ConnectionUpdated;
import dev.msgforge.event.GatewayEvent.MessageReceived;
import dev.msgforge.event.GatewayEvent.MessageSent;
import dev.msgforge.event.GatewayEvent.QrCodeUpdated;
import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.instance.InstanceStateService;
import dev.msgforge.webhook.WebhookService;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converte {@link GatewayEvent} no envelope da Evolution e entrega em webhook
 * (da instância e global) e RabbitMQ.
 *
 * <p>Dois tempos (ver docs/decisoes/0004):
 * <ol>
 *   <li>Síncrono, na thread e na transação de quem publicou: atualiza o estado e copia
 *       os dados da instância e do webhook para um {@link DeliveryTarget}.</li>
 *   <li>Assíncrono, numa virtual thread, só <b>depois do commit</b> (ou na hora, se não
 *       houver transação): entrega usando apenas o snapshot. Em rollback, nada é entregue.</li>
 * </ol>
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final MsgforgeProperties properties;
    private final InstanceRepository instances;
    private final InstanceStateService stateService;
    private final WebhookService webhooks;
    private final WebhookSender sender;
    private final RabbitTemplate rabbit;
    private final JsonMapper json;

    public EventDispatcher(MsgforgeProperties properties, InstanceRepository instances, InstanceStateService stateService,
                           WebhookService webhooks, WebhookSender sender, RabbitTemplate rabbit, JsonMapper json) {
        this.properties = properties;
        this.instances = instances;
        this.stateService = stateService;
        this.webhooks = webhooks;
        this.sender = sender;
        this.rabbit = rabbit;
        this.json = json;
    }

    /** Envelope idêntico ao payload de webhook da Evolution. */
    public record EventEnvelope(
            String event,
            String instance,
            Object data,
            String destination,
            @JsonProperty("date_time") String dateTime,
            String sender,
            @JsonProperty("server_url") String serverUrl,
            String apikey) {}

    @EventListener
    public void on(GatewayEvent event) {
        var type = switch (event) {
            case QrCodeUpdated _ -> EventType.QRCODE_UPDATED;
            case ConnectionUpdated c -> {
                stateService.update(c.instanceName(), c.state());
                yield EventType.CONNECTION_UPDATE;
            }
            case MessageSent _ -> EventType.SEND_MESSAGE;
            case MessageReceived _ -> EventType.MESSAGES_UPSERT;
        };
        var target = snapshot(event.instanceName());
        if (target == null) {
            log.debug("Evento {} ignorado: instância {} não existe", type, event.instanceName());
            return;
        }
        var data = dataOf(event);
        var dateTime = OffsetDateTime.now().toString();
        Runnable delivery = () -> executor.submit(() -> deliver(target, type, data, dateTime));

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delivery.run();
                }
            });
        } else {
            delivery.run();
        }
    }

    /** Lido na transação de quem publicou: enxerga a instância recém-criada e ainda não commitada. */
    private DeliveryTarget snapshot(String instanceName) {
        var instance = instances.findByName(instanceName).orElse(null);
        if (instance == null) {
            return null;
        }
        var webhook = webhooks.findEntity(instance)
                .map(w -> new WebhookTarget(w.getUrl(), w.isEnabled(), w.isByEvents(), w.getEvents(),
                        webhooks.headersOf(w)))
                .orElse(null);
        return new DeliveryTarget(instance.getName(), instance.getToken(), instance.getOwnerJid(), webhook);
    }

    private static Object dataOf(GatewayEvent event) {
        return switch (event) {
            case QrCodeUpdated q -> Map.of("qrcode", nonNull(Map.of(
                    "instance", q.instanceName()), "code", q.code(), "pairingCode", q.pairingCode()));
            case ConnectionUpdated c -> nonNull(Map.of(
                    "instance", c.instanceName(), "state", c.state().value()), "statusReason", c.statusReason());
            case MessageSent m -> Map.of(
                    "key", Map.of("remoteJid", m.message().remoteJid(), "fromMe", true, "id", m.message().id()),
                    "messageTimestamp", m.message().timestamp(),
                    "status", "PENDING");
            case MessageReceived r -> r.data();
        };
    }

    private void deliver(DeliveryTarget target, EventType type, Object data, String dateTime) {
        var webhook = target.webhook();
        if (webhook != null && webhook.accepts(type)) {
            var url = webhook.urlFor(type);
            sender.send(url, type.payloadName(), envelope(target, type, data, url, dateTime), webhook.headers());
        }

        var global = properties.webhook();
        if (global.globalEnabled() && global.globalUrl() != null && !global.globalUrl().isBlank()) {
            sender.send(global.globalUrl(), type.payloadName(),
                    envelope(target, type, data, global.globalUrl(), dateTime), Map.of());
        }

        if (properties.rabbitmq().enabled()) {
            publishRabbit(target.instanceName(), type, envelope(target, type, data, null, dateTime));
        }
    }

    private EventEnvelope envelope(DeliveryTarget target, EventType type, Object data, String destination,
                                   String dateTime) {
        return new EventEnvelope(type.payloadName(), target.instanceName(), data, destination,
                dateTime, target.ownerJid(), properties.serverUrl(), target.token());
    }

    private void publishRabbit(String instance, EventType type, EventEnvelope body) {
        try {
            var props = new MessageProperties();
            props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            var message = new org.springframework.amqp.core.Message(json.writeValueAsBytes(body), props);
            rabbit.send(properties.rabbitmq().exchange(), instance + "." + type.payloadName(), message);
        } catch (Exception e) {
            log.warn("Falha ao publicar {} no RabbitMQ: {}", type, e.getMessage());
        }
    }

    /** Map.of não aceita null; adiciona a chave só se o valor existir. */
    private static Map<String, Object> nonNull(Map<String, Object> base, String key, Object value) {
        var map = new LinkedHashMap<String, Object>(base);
        if (value != null) {
            map.put(key, value);
        }
        return map;
    }

    private static Map<String, Object> nonNull(Map<String, Object> base, String k1, Object v1, String k2, Object v2) {
        return nonNull(nonNull(base, k1, v1), k2, v2);
    }
}

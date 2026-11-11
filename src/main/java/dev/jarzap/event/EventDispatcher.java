package dev.jarzap.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.jarzap.config.JarZapProperties;
import dev.jarzap.event.GatewayEvent.ConnectionUpdated;
import dev.jarzap.event.GatewayEvent.MessageReceived;
import dev.jarzap.event.GatewayEvent.MessageSent;
import dev.jarzap.event.GatewayEvent.QrCodeUpdated;
import dev.jarzap.instance.InstanceRepository;
import dev.jarzap.instance.InstanceStateService;
import dev.jarzap.webhook.WebhookService;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converte {@link GatewayEvent} no envelope da Evolution e entrega em webhook
 * (da instância e global) e RabbitMQ. Cada entrega roda em uma virtual thread.
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final JarZapProperties properties;
    private final InstanceRepository instances;
    private final InstanceStateService stateService;
    private final WebhookService webhooks;
    private final RestClient http;
    private final RabbitTemplate rabbit;
    private final JsonMapper json;

    public EventDispatcher(JarZapProperties properties, InstanceRepository instances, InstanceStateService stateService,
                           WebhookService webhooks, RestClient webhookRestClient, RabbitTemplate rabbit, JsonMapper json) {
        this.properties = properties;
        this.instances = instances;
        this.stateService = stateService;
        this.webhooks = webhooks;
        this.http = webhookRestClient;
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
        var data = dataOf(event);
        executor.submit(() -> deliver(event.instanceName(), type, data));
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

    private void deliver(String instanceName, EventType type, Object data) {
        var instance = instances.findByName(instanceName).orElse(null);
        if (instance == null) {
            return;
        }
        var webhook = webhooks.findEntity(instance).orElse(null);

        if (webhook != null && webhook.isEnabled() && accepts(webhook.getEvents(), type)) {
            var url = webhook.isByEvents() ? webhook.getUrl() + type.urlSuffix() : webhook.getUrl();
            post(url, envelope(instanceName, type, data, url, instance.getOwnerJid(), instance.getToken()),
                    webhooks.headersOf(webhook));
        }

        var global = properties.webhook();
        if (global.globalEnabled() && global.globalUrl() != null && !global.globalUrl().isBlank()) {
            post(global.globalUrl(), envelope(instanceName, type, data, global.globalUrl(), instance.getOwnerJid(),
                    instance.getToken()), Map.of());
        }

        if (properties.rabbitmq().enabled()) {
            publishRabbit(instanceName, type, envelope(instanceName, type, data, null, instance.getOwnerJid(),
                    instance.getToken()));
        }
    }

    private static boolean accepts(java.util.List<String> events, EventType type) {
        return events.isEmpty() || events.contains(type.name());
    }

    private EventEnvelope envelope(String instance, EventType type, Object data, String destination,
                                   String sender, String apikey) {
        return new EventEnvelope(type.payloadName(), instance, data, destination,
                OffsetDateTime.now().toString(), sender, properties.serverUrl(), apikey);
    }

    private void post(String url, EventEnvelope body, Map<String, String> headers) {
        try {
            http.post().uri(url)
                    .headers(h -> headers.forEach(h::add))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Falha ao enviar webhook {} para {}: {}", body.event(), url, e.getMessage());
        }
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

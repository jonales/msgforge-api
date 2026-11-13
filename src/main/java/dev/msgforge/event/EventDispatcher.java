package dev.msgforge.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.event.DeliveryTarget.WebhookTarget;
import dev.msgforge.event.GatewayEvent.ConnectionUpdated;
import dev.msgforge.event.GatewayEvent.MessageReceived;
import dev.msgforge.event.GatewayEvent.MessageSent;
import dev.msgforge.event.GatewayEvent.MessageStatusUpdated;
import dev.msgforge.event.GatewayEvent.QrCodeUpdated;
import dev.msgforge.event.outbox.Outbox;
import dev.msgforge.event.outbox.OutboxProperties;
import dev.msgforge.event.outbox.OutboxWorker;
import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.instance.InstanceStateService;
import dev.msgforge.webhook.WebhookService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converte {@link GatewayEvent} no envelope de evento do contrato e entrega em webhook
 * (da instância e global) e RabbitMQ.
 *
 * <p>Dois tempos (ver docs/decisoes/0004):
 * <ol>
 *   <li>Síncrono, na thread e na transação de quem publicou: atualiza o estado e copia
 *       os dados da instância e do webhook para um {@link DeliveryTarget}.</li>
 *   <li>Assíncrono, numa virtual thread, só <b>depois do commit</b> (ou na hora, se não
 *       houver transação): entrega usando apenas o snapshot. Em rollback, nada é entregue.</li>
 * </ol>
 * As entregas de uma mesma instância saem na ordem dos eventos (ADR 0006), instâncias diferentes
 * em paralelo. Com OUTBOX_ENABLED (padrão), o segundo tempo grava as entregas na outbox, numa
 * transação própria, e o {@link OutboxWorker} entrega (ADR 0012); desligado, a fila é a em memória
 * ({@link OrderedExecutor}). O corpo que sai para o destino é o mesmo nos dois caminhos.
 */
@Component
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final OrderedExecutor deliveries;
    private final MsgforgeProperties properties;
    private final InstanceRepository instances;
    private final InstanceStateService stateService;
    private final WebhookService webhooks;
    private final WebhookSender sender;
    private final RabbitTemplate rabbit;
    private final JsonMapper json;
    private final Outbox outbox;
    private final OutboxProperties outboxProperties;
    private final ObjectProvider<OutboxWorker> outboxWorker;
    private final boolean blockPrivateNetworks;

    public EventDispatcher(MsgforgeProperties properties, InstanceRepository instances, InstanceStateService stateService,
                           WebhookService webhooks, WebhookSender sender, RabbitTemplate rabbit, JsonMapper json,
                           Outbox outbox, OutboxProperties outboxProperties, ObjectProvider<OutboxWorker> outboxWorker) {
        this.properties = properties;
        this.deliveries = new OrderedExecutor(Executors.newVirtualThreadPerTaskExecutor(),
                properties.webhook().maxPendingPerInstance());
        this.instances = instances;
        this.stateService = stateService;
        this.webhooks = webhooks;
        this.sender = sender;
        this.rabbit = rabbit;
        this.json = json;
        this.outbox = outbox;
        this.outboxProperties = outboxProperties;
        this.outboxWorker = outboxWorker;
        this.blockPrivateNetworks = properties.webhook().blockPrivateNetworks();
    }

    /** Envelope de evento do contrato da API (docs/COMPATIBILIDADE.md). */
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
                stateService.update(c.instanceName(), c.state(), c.wuid(), c.profileName());
                yield EventType.CONNECTION_UPDATE;
            }
            case MessageSent _ -> EventType.SEND_MESSAGE;
            case MessageReceived _ -> EventType.MESSAGES_UPSERT;
            case MessageStatusUpdated _ -> EventType.MESSAGES_UPDATE;
        };
        var target = snapshot(event.instanceName());
        if (target == null) {
            log.debug("Evento {} ignorado: instância {} não existe", type, event.instanceName());
            return;
        }
        var data = dataOf(event);
        var dateTime = OffsetDateTime.now().toString();
        Runnable delivery = outboxProperties.enabled()
                ? () -> enqueue(target, type, data, dateTime)
                : () -> submitInMemory(target, type, data, dateTime);

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
            case QrCodeUpdated q -> Map.of("qrcode", nonNull(nonNull(Map.of(
                    "instance", q.instanceName()), "code", q.code(), "pairingCode", q.pairingCode()),
                    "base64", q.base64()));
            case ConnectionUpdated c -> {
                var map = nonNull(Map.of("instance", c.instanceName(), "state", c.state().value()),
                        "statusReason", c.statusReason());
                yield nonNull(map, "wuid", c.wuid(), "profileName", c.profileName());
            }
            case MessageSent m -> Map.of(
                    "key", Map.of("remoteJid", m.message().remoteJid(), "fromMe", true, "id", m.message().id()),
                    "messageTimestamp", m.message().timestamp(),
                    "status", "PENDING");
            case MessageReceived r -> r.data();
            case MessageStatusUpdated s -> s.data();
        };
    }

    /** Uma entrega de um evento: destino e envelope, iguais na outbox e na fila em memória. */
    private record Delivery(Outbox.Type type, String destination, Map<String, String> headers, EventEnvelope body) {}

    private List<Delivery> deliveriesOf(DeliveryTarget target, EventType type, Object data, String dateTime) {
        var list = new ArrayList<Delivery>(3);
        var webhook = target.webhook();
        if (webhook != null && webhook.accepts(type)) {
            var url = webhook.urlFor(type);
            list.add(new Delivery(Outbox.Type.WEBHOOK, url, webhook.headers(),
                    envelope(target, type, data, url, dateTime)));
        }

        var global = properties.webhook();
        if (global.globalEnabled() && global.globalUrl() != null && !global.globalUrl().isBlank()) {
            list.add(new Delivery(Outbox.Type.WEBHOOK_GLOBAL, global.globalUrl(), Map.of(),
                    envelope(target, type, data, global.globalUrl(), dateTime)));
        }

        if (properties.rabbitmq().enabled()) {
            list.add(new Delivery(Outbox.Type.RABBITMQ, properties.rabbitmq().exchange(), Map.of(),
                    envelope(target, type, data, null, dateTime)));
        }
        return list;
    }

    /** Fila em memória (OUTBOX_ENABLED=false), com teto por instância (ADR 0006). */
    private void submitInMemory(DeliveryTarget target, EventType type, Object data, String dateTime) {
        if (!deliveries.submit(target.instanceName(), () -> deliver(target, type, data, dateTime))) {
            log.warn("Evento {} da instância {} descartado: {} entregas já aguardando",
                    type, target.instanceName(), properties.webhook().maxPendingPerInstance());
        }
    }

    private void deliver(DeliveryTarget target, EventType type, Object data, String dateTime) {
        for (var d : deliveriesOf(target, type, data, dateTime)) {
            switch (d.type()) {
                case WEBHOOK -> sender.sendUntrusted(d.destination(), type.payloadName(), d.body(), d.headers());
                case WEBHOOK_GLOBAL -> sender.send(d.destination(), type.payloadName(), d.body(), d.headers());
                case RABBITMQ -> publishRabbit(target.instanceName(), type, d.body());
            }
        }
    }

    /** Outbox (ADR 0012): grava antes de entregar; o trabalhador entrega a partir da linha. */
    private void enqueue(DeliveryTarget target, EventType type, Object data, String dateTime) {
        var entries = deliveriesOf(target, type, data, dateTime).stream()
                .map(d -> new Outbox.NewEntry(d.type(), target.instanceName(), type.payloadName(), d.destination(),
                        d.headers(), d.type() == Outbox.Type.WEBHOOK && blockPrivateNetworks,
                        json.writeValueAsString(d.body())))
                .toList();
        if (entries.isEmpty()) {
            return;
        }
        try {
            outbox.insert(entries);
        } catch (RuntimeException e) {
            log.error("Evento {} da instância {} perdido: falha ao gravar na outbox: {}",
                    type, target.instanceName(), e.getMessage(), e);
            return;
        }
        outboxWorker.ifAvailable(OutboxWorker::wake);
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

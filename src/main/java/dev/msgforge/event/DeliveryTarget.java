package dev.msgforge.event;

import java.util.List;
import java.util.Map;

/**
 * Tudo o que a entrega de um evento precisa saber sobre a instância, copiado no momento
 * em que o evento acontece. A thread de entrega nunca consulta o banco: ela pode rodar
 * depois de a instância ter sido apagada (delete) ou antes de ela estar visível para
 * outras conexões.
 */
record DeliveryTarget(String instanceName, String instanceId, String token, String ownerJid, WebhookTarget webhook) {

    /** APPLICATION_STARTUP: sem instância, sem webhook de instância. */
    static final DeliveryTarget APPLICATION = new DeliveryTarget(null, null, null, null, null);

    /** Chave da fila ordenada (e da outbox): a instância, ou "" para os eventos da aplicação. */
    String orderKey() {
        return instanceName == null ? "" : instanceName;
    }

    record WebhookTarget(String url, boolean enabled, boolean byEvents, List<String> events,
                         Map<String, String> headers) {

        boolean accepts(EventType type) {
            return enabled && (events.isEmpty() || events.contains(type.name()));
        }

        String urlFor(EventType type) {
            return byEvents ? url + type.urlSuffix() : url;
        }
    }
}

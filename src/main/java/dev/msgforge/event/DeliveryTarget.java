package dev.msgforge.event;

import java.util.List;
import java.util.Map;

/**
 * Tudo o que a entrega de um evento precisa saber sobre a instância, copiado no momento
 * em que o evento acontece. A thread de entrega nunca consulta o banco: ela pode rodar
 * depois de a instância ter sido apagada (delete) ou antes de ela estar visível para
 * outras conexões.
 */
record DeliveryTarget(String instanceName, String token, String ownerJid, WebhookTarget webhook) {

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

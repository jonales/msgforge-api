package dev.msgforge.web;

import dev.msgforge.event.GatewayEvent;
import dev.msgforge.group.GroupService;
import dev.msgforge.gateway.WhatsAppGateway.GroupInfo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Nomes dos grupos para a tela de mensagens. O histórico não guarda o nome do grupo (o pushName
 * das mensagens é de quem mandou), então o nome vem do motor, numa consulta só por instância
 * ({@link GroupService#all}), guardada por alguns minutos: a lista de conversas é renderizada a cada
 * mensagem pelo SSE. Instância desconectada ou falha na consulta: sem nomes (a tela mostra o JID).
 * Um grupo criado, renomeado ou que chegou pelo motor descarta o que estava guardado.
 */
@Component
public class GroupNames {

    static final Duration TTL = Duration.ofMinutes(5);
    static final Duration FAILURE_TTL = Duration.ofMinutes(1);

    private record Entry(Map<String, String> names, Instant expires) {}

    private final GroupService groups;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public GroupNames(GroupService groups) {
        this(groups, Clock.systemUTC());
    }

    GroupNames(GroupService groups, Clock clock) {
        this.groups = groups;
        this.clock = clock;
    }

    /** JID do grupo → nome, para a instância. Nunca lança: sem consulta possível, vazio. */
    public Map<String, String> of(String instanceName) {
        var now = clock.instant();
        var entry = cache.get(instanceName);
        if (entry != null && now.isBefore(entry.expires())) {
            return entry.names();
        }
        Entry fresh;
        try {
            var names = new HashMap<String, String>();
            for (GroupInfo group : groups.all(instanceName)) {
                if (group.subject() != null && !group.subject().isBlank()) {
                    names.put(group.id(), group.subject());
                }
            }
            fresh = new Entry(Map.copyOf(names), now.plus(TTL));
        } catch (RuntimeException e) {
            fresh = new Entry(Map.of(), now.plus(FAILURE_TTL));
        }
        cache.put(instanceName, fresh);
        return fresh.names();
    }

    @EventListener
    void on(GatewayEvent.GroupsUpserted event) {
        cache.remove(event.instanceName());
    }

    @EventListener
    void on(GatewayEvent.GroupUpdated event) {
        cache.remove(event.instanceName());
    }

    @EventListener
    void on(GatewayEvent.ConnectionUpdated event) {
        cache.remove(event.instanceName());
    }
}

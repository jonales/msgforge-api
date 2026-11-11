package dev.msgforge.instance;

import dev.msgforge.gateway.session.SessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * No boot, reconecta as instâncias que têm sessão salva.
 * Sem sessão, a instância fica "close" até alguém chamar /instance/connect.
 */
@Component
public class SessionRestorer {

    private static final Logger log = LoggerFactory.getLogger(SessionRestorer.class);

    private final InstanceRepository instances;
    private final SessionStore sessions;
    private final InstanceService service;

    public SessionRestorer(InstanceRepository instances, SessionStore sessions, InstanceService service) {
        this.instances = instances;
        this.sessions = sessions;
        this.service = service;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        var restored = restoreAll();
        if (restored > 0) {
            log.info("{} instância(s) com sessão salva reconectada(s)", restored);
        }
    }

    /** @return quantas instâncias foram reconectadas */
    public int restoreAll() {
        int count = 0;
        for (var instance : instances.findAll()) {
            if (!sessions.exists(instance.getId())) {
                continue;
            }
            try {
                service.connect(instance.getName(), null);
                count++;
            } catch (RuntimeException e) {
                log.warn("Falha ao reconectar {}: {}", instance.getName(), e.getMessage());
            }
        }
        return count;
    }
}

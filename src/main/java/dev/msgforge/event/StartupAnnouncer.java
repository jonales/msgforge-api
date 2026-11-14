package dev.msgforge.event;

import dev.msgforge.event.GatewayEvent.ApplicationStartup;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Publica o APPLICATION_STARTUP quando a aplicação fica pronta. Não pertence a nenhuma instância:
 * vai só para o webhook global e o RabbitMQ.
 */
@Component
class StartupAnnouncer {

    private final ApplicationEventPublisher events;

    StartupAnnouncer(ApplicationEventPublisher events) {
        this.events = events;
    }

    @EventListener(ApplicationReadyEvent.class)
    void ready() {
        events.publishEvent(new ApplicationStartup());
    }
}

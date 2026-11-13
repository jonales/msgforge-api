package dev.msgforge.web;

import dev.msgforge.event.GatewayEvent;
import dev.msgforge.event.GatewayEvent.ConnectionUpdated;
import dev.msgforge.event.GatewayEvent.MessageReceived;
import dev.msgforge.event.GatewayEvent.MessageSent;
import dev.msgforge.event.GatewayEvent.MessageStatusUpdated;
import dev.msgforge.event.GatewayEvent.QrCodeUpdated;
import dev.msgforge.web.ManagerUpdate.Change;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Traduz os {@link GatewayEvent} em {@link ManagerUpdate} e publica no {@link ManagerBus}. Escuta
 * depois do commit (ou na hora, sem transação) e por último, para que o estado, o QR e o
 * histórico já estejam gravados quando o trecho for renderizado. Não muda nada nos eventos da API.
 */
@Component
class ManagerEventBridge {

    private final ManagerBus bus;

    ManagerEventBridge(ManagerBus bus) {
        this.bus = bus;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void on(GatewayEvent event) {
        var name = event.instanceName();
        var update = switch (event) {
            case QrCodeUpdated _ -> new ManagerUpdate(name, Change.QR, null);
            case ConnectionUpdated _ -> new ManagerUpdate(name, Change.CONNECTION, null);
            case MessageSent m -> new ManagerUpdate(name, Change.MESSAGES, m.message().remoteJid());
            case MessageReceived r -> new ManagerUpdate(name, Change.MESSAGES,
                    r.data().get("key") instanceof Map<?, ?> key ? text(key.get("remoteJid")) : null);
            case MessageStatusUpdated s -> new ManagerUpdate(name, Change.MESSAGES, text(s.data().get("remoteJid")));
        };
        bus.publish(update);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}

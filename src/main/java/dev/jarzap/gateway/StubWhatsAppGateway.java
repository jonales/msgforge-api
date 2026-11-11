package dev.jarzap.gateway;

import dev.jarzap.event.GatewayEvent;
import dev.jarzap.instance.ConnectionState;
import dev.jarzap.instance.Instance;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Gateway falso para desenvolver e testar o contrato da API sem número real.
 * Ativo com jarzap.gateway=stub (padrão).
 */
@Component
@ConditionalOnProperty(name = "jarzap.gateway", havingValue = "stub", matchIfMissing = true)
public class StubWhatsAppGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(StubWhatsAppGateway.class);

    private final ApplicationEventPublisher events;

    public StubWhatsAppGateway(ApplicationEventPublisher events) {
        this.events = events;
    }

    @Override
    public ConnectResult connect(Instance instance, String phoneNumberForPairing) {
        var qr = "stub-qr-" + UUID.randomUUID();
        var pairing = phoneNumberForPairing == null ? null : "STUB-1234";
        log.info("[stub] connect {} -> QR gerado", instance.getName());
        events.publishEvent(new GatewayEvent.QrCodeUpdated(instance.getName(), qr, pairing));
        events.publishEvent(new GatewayEvent.ConnectionUpdated(instance.getName(), ConnectionState.CONNECTING, null));
        return new ConnectResult(pairing, qr, null);
    }

    @Override
    public void logout(Instance instance) {
        log.info("[stub] logout {}", instance.getName());
        events.publishEvent(new GatewayEvent.ConnectionUpdated(instance.getName(), ConnectionState.CLOSE, 401));
    }

    @Override
    public SentMessage sendText(Instance instance, OutgoingText text) {
        log.info("[stub] sendText {} -> {}: {}", instance.getName(), text.jid(), text.text());
        return sent(instance, text.jid());
    }

    @Override
    public SentMessage sendMedia(Instance instance, OutgoingMedia media) {
        log.info("[stub] sendMedia {} -> {} ({})", instance.getName(), media.jid(), media.mediaType());
        return sent(instance, media.jid());
    }

    private SentMessage sent(Instance instance, String jid) {
        var id = "3EB0" + HexFormat.of().withUpperCase().formatHex(UUID.randomUUID().toString().getBytes(), 0, 8);
        var message = new SentMessage(id, jid, Instant.now().getEpochSecond());
        events.publishEvent(new GatewayEvent.MessageSent(instance.getName(), message));
        return message;
    }
}

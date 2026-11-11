package dev.msgforge.gateway;

import dev.msgforge.event.GatewayEvent;
import dev.msgforge.instance.ConnectionState;
import dev.msgforge.instance.Instance;
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
 * Ativo com msgforge.gateway=stub (padrão).
 */
@Component
@ConditionalOnProperty(name = "msgforge.gateway", havingValue = "stub", matchIfMissing = true)
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

    @Override
    public SentMessage send(Instance instance, Outgoing message) {
        var kind = switch (message) {
            case VoiceNote _ -> "áudio de voz";
            case Location l -> "localização " + l.latitude() + "," + l.longitude();
            case Contacts c -> c.contacts().size() + " contato(s)";
            case Reaction r -> "reação '" + r.emoji() + "' em " + r.messageId();
            case Poll p -> "enquete '" + p.name() + "' (" + p.values().size() + " opções)";
        };
        log.info("[stub] send {} -> {}: {}", instance.getName(), message.jid(), kind);
        return sent(instance, message.jid());
    }

    /** No stub, todo número bem formado "tem WhatsApp". */
    @Override
    public java.util.Map<String, Boolean> checkNumbers(Instance instance, java.util.List<String> jids) {
        var result = new java.util.LinkedHashMap<String, Boolean>();
        jids.forEach(jid -> result.put(jid, true));
        return result;
    }

    @Override
    public void markRead(Instance instance, java.util.List<MessageKey> keys) {
        log.info("[stub] markRead {}: {} mensagem(ns)", instance.getName(), keys.size());
    }

    @Override
    public void sendPresence(Instance instance, String jid, Presence presence, Integer delayMs) {
        log.info("[stub] presença {} -> {}: {}", instance.getName(), jid, presence);
    }

    @Override
    public Profile fetchProfile(Instance instance, String jid) {
        return new Profile(jid, true, null, null, null);
    }

    @Override
    public String profilePictureUrl(Instance instance, String jid) {
        return null;
    }

    @Override
    public void updateProfile(Instance instance, ProfileUpdate update) {
        var what = switch (update) {
            case ProfileName n -> "nome '" + n.name() + "'";
            case ProfileStatus st -> "recado '" + st.status() + "'";
            case ProfilePicture _ -> "foto";
        };
        log.info("[stub] perfil {}: {}", instance.getName(), what);
    }

    private SentMessage sent(Instance instance, String jid) {
        var id = "3EB0" + HexFormat.of().withUpperCase().formatHex(UUID.randomUUID().toString().getBytes(), 0, 8);
        var message = new SentMessage(id, jid, Instant.now().getEpochSecond());
        events.publishEvent(new GatewayEvent.MessageSent(instance.getName(), message));
        return message;
    }
}

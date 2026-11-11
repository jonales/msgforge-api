package dev.msgforge.message;

import dev.msgforge.gateway.Jids;
import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.gateway.WhatsAppGateway.OutgoingMedia;
import dev.msgforge.gateway.WhatsAppGateway.OutgoingText;
import dev.msgforge.gateway.WhatsAppGateway.SentMessage;
import dev.msgforge.instance.Instance;
import dev.msgforge.instance.InstanceService;
import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import java.util.List;
import org.springframework.stereotype.Service;

/** Envio de mensagens, usado pela API (/message/*) e pelo Manager. */
@Service
public class MessageService {

    private final InstanceService instances;
    private final WhatsAppGateway gateway;

    public MessageService(InstanceService instances, WhatsAppGateway gateway) {
        this.instances = instances;
        this.gateway = gateway;
    }

    public record Sent(Instance instance, SentMessage message) {}

    public Sent sendText(String instanceName, String number, String text, Integer delay, Boolean linkPreview) {
        var instance = instances.get(instanceName);
        var sent = gateway.sendText(instance, new OutgoingText(Jids.toJid(number), text, delay, linkPreview));
        return new Sent(instance, sent);
    }

    /** {@code encoding}: converter para OGG/Opus (padrão: sim). */
    public Sent sendVoice(String instanceName, String number, String audio, Integer delay, Boolean encoding) {
        return send(instanceName, new WhatsAppGateway.VoiceNote(Jids.toJid(number), audio, delay,
                !Boolean.FALSE.equals(encoding)));
    }

    public Sent sendLocation(String instanceName, String number, double latitude, double longitude,
                             String name, String address, Integer delay) {
        return send(instanceName,
                new WhatsAppGateway.Location(Jids.toJid(number), latitude, longitude, name, address, delay));
    }

    public Sent sendContacts(String instanceName, String number, List<MessageDtos.ContactEntry> contacts,
                             Integer delay) {
        var cards = contacts.stream()
                .map(c -> new WhatsAppGateway.ContactCard(c.fullName(), VCards.of(c)))
                .toList();
        return send(instanceName, new WhatsAppGateway.Contacts(Jids.toJid(number), cards, delay));
    }

    public Sent sendReaction(String instanceName, String remoteJid, String messageId, boolean fromMe,
                             String emoji) {
        return send(instanceName, new WhatsAppGateway.Reaction(Jids.toJid(remoteJid), messageId, fromMe, emoji));
    }

    /** {@code selectableCount}: 1 = escolha única; 0/nulo ou maior que o número de opções = várias. */
    public Sent sendPoll(String instanceName, String number, String name, Integer selectableCount,
                         List<String> values, Integer delay) {
        if (values.stream().map(String::trim).distinct().count() != values.size()) {
            throw new BadRequestException("Poll values must be unique");
        }
        var count = selectableCount == null || selectableCount > values.size() ? 0 : selectableCount;
        return send(instanceName, new WhatsAppGateway.Poll(Jids.toJid(number), name, count, values, delay));
    }

    private Sent send(String instanceName, WhatsAppGateway.Outgoing message) {
        var instance = instances.get(instanceName);
        return new Sent(instance, gateway.send(instance, message));
    }

    public Sent sendMedia(String instanceName, String number, String mediaType, String mimeType, String media,
                          String caption, String fileName, Integer delay) {
        var instance = instances.get(instanceName);
        var sent = gateway.sendMedia(instance,
                new OutgoingMedia(Jids.toJid(number), mediaType, mimeType, media, caption, fileName, delay));
        return new Sent(instance, sent);
    }
}

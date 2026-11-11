package dev.msgforge.message;

import dev.msgforge.gateway.Jids;
import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.gateway.WhatsAppGateway.OutgoingMedia;
import dev.msgforge.gateway.WhatsAppGateway.OutgoingText;
import dev.msgforge.gateway.WhatsAppGateway.SentMessage;
import dev.msgforge.instance.Instance;
import dev.msgforge.instance.InstanceService;
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

    public Sent sendMedia(String instanceName, String number, String mediaType, String mimeType, String media,
                          String caption, String fileName, Integer delay) {
        var instance = instances.get(instanceName);
        var sent = gateway.sendMedia(instance,
                new OutgoingMedia(Jids.toJid(number), mediaType, mimeType, media, caption, fileName, delay));
        return new Sent(instance, sent);
    }
}

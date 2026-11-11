package dev.msgforge.chat;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.gateway.Jids;
import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.gateway.WhatsAppGateway.MessageKey;
import dev.msgforge.gateway.WhatsAppGateway.Presence;
import dev.msgforge.gateway.WhatsAppGateway.Profile;
import dev.msgforge.gateway.WhatsAppGateway.ProfileUpdate;
import dev.msgforge.instance.InstanceService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Conversas e perfil (/chat/*). */
@Service
public class ChatService {

    private final InstanceService instances;
    private final WhatsAppGateway gateway;

    public ChatService(InstanceService instances, WhatsAppGateway gateway) {
        this.instances = instances;
        this.gateway = gateway;
    }

    /** Números com formato inválido voltam como inexistentes, sem consultar o WhatsApp. */
    public List<ChatDtos.NumberStatus> whatsappNumbers(String instanceName, List<String> numbers) {
        var instance = instances.get(instanceName);
        var jids = new LinkedHashMap<String, String>();   // número -> JID (null se inválido)
        for (var number : numbers) {
            jids.put(number, phoneJid(number));
        }
        var query = jids.values().stream().filter(jid -> jid != null).distinct().toList();
        var exists = query.isEmpty() ? java.util.Map.<String, Boolean>of() : gateway.checkNumbers(instance, query);
        var result = new ArrayList<ChatDtos.NumberStatus>();
        jids.forEach((number, jid) -> result.add(
                new ChatDtos.NumberStatus(jid != null && Boolean.TRUE.equals(exists.get(jid)), jid, number)));
        return result;
    }

    private static String phoneJid(String number) {
        try {
            var jid = Jids.toJid(number);
            return jid.endsWith("@s.whatsapp.net") ? jid : null;
        } catch (BadRequestException e) {
            return null;
        }
    }

    public void markAsRead(String instanceName, List<ChatDtos.ReadMessage> messages) {
        var instance = instances.get(instanceName);
        var keys = messages.stream()
                .map(m -> new MessageKey(Jids.toJid(m.remoteJid()), m.id(), Boolean.TRUE.equals(m.fromMe())))
                .toList();
        gateway.markRead(instance, keys);
    }

    public Presence sendPresence(String instanceName, String number, String presence, Integer delay) {
        var instance = instances.get(instanceName);
        var value = Presence.valueOf(presence.toUpperCase(Locale.ROOT));
        gateway.sendPresence(instance, Jids.toJid(number), value, delay);
        return value;
    }

    /** Sem {@code number}: o perfil da própria conta (precisa estar conectada). */
    public Profile fetchProfile(String instanceName, String number) {
        var instance = instances.get(instanceName);
        String jid;
        if (number == null || number.isBlank()) {
            jid = instance.getOwnerJid();
            if (jid == null) {
                throw new BadRequestException("The \"" + instanceName + "\" instance is not connected");
            }
        } else {
            jid = Jids.toJid(number);
        }
        return gateway.fetchProfile(instance, jid);
    }

    public ChatDtos.ProfilePictureResponse profilePictureUrl(String instanceName, String number) {
        var instance = instances.get(instanceName);
        var jid = Jids.toJid(number);
        return new ChatDtos.ProfilePictureResponse(jid, gateway.profilePictureUrl(instance, jid));
    }

    public void updateProfile(String instanceName, ProfileUpdate update) {
        gateway.updateProfile(instances.get(instanceName), update);
    }
}

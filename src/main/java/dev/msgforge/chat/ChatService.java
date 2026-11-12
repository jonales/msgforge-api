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
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Conversas e perfil (/chat/*). */
@Service
public class ChatService {

    static final int DEFAULT_PAGE_SIZE = 50;

    private final InstanceService instances;
    private final WhatsAppGateway gateway;
    private final dev.msgforge.history.MessageHistory history;

    public ChatService(InstanceService instances, WhatsAppGateway gateway, dev.msgforge.history.MessageHistory history) {
        this.instances = instances;
        this.gateway = gateway;
        this.history = history;
    }

    /** Histórico gravado no banco; filtros da key opcionais, mais novas primeiro. */
    public ChatDtos.MessagesPage findMessages(String instanceName, ChatDtos.KeyFilter key, Integer page, Integer size) {
        var instance = instances.get(instanceName);
        var remoteJid = key == null || key.remoteJid() == null || key.remoteJid().isBlank() ? null : Jids.toJid(key.remoteJid());
        var fromMe = key == null ? null : key.fromMe();
        var id = key == null || key.id() == null || key.id().isBlank() ? null : key.id();
        var pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        var current = page == null ? 1 : page;
        var total = history.count(instance.getId(), remoteJid, fromMe, id);
        var records = history.find(instance.getId(), remoteJid, fromMe, id, current, pageSize).stream()
                .map(ChatDtos.MessageRecord::of).toList();
        return new ChatDtos.MessagesPage(total, (total + pageSize - 1) / pageSize, current, records);
    }

    public List<ChatDtos.ChatSummary> findChats(String instanceName) {
        var instance = instances.get(instanceName);
        return history.chats(instance.getId()).stream()
                .map(chat -> new ChatDtos.ChatSummary(chat.remoteJid(), chat.pushName(),
                        java.time.Instant.ofEpochSecond(chat.lastMessage().messageTimestamp()).toString(),
                        ChatDtos.MessageRecord.of(chat.lastMessage())))
                .toList();
    }

    /**
     * Uma resposta por número pedido, na ordem (repetidos também). Formato inválido volta como
     * inexistente, sem consultar o WhatsApp. {@code jid}: o que o WhatsApp conhece, se existir.
     */
    public List<ChatDtos.NumberStatus> whatsappNumbers(String instanceName, List<String> numbers) {
        var instance = instances.get(instanceName);
        var jids = numbers.stream().map(ChatService::phoneJid).toList();   // null se inválido
        var query = jids.stream().filter(jid -> jid != null).distinct().toList();
        var known = query.isEmpty() ? java.util.Map.<String, String>of() : gateway.checkNumbers(instance, query);
        var result = new ArrayList<ChatDtos.NumberStatus>();
        for (var i = 0; i < numbers.size(); i++) {
            var jid = jids.get(i);
            var real = jid == null ? null : known.get(jid);
            result.add(new ChatDtos.NumberStatus(real != null, real != null ? real : jid, numbers.get(i)));
        }
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

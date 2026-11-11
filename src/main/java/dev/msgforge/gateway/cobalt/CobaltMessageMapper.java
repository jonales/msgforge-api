package dev.msgforge.gateway.cobalt;

import it.auties.whatsapp.model.info.ChatMessageInfo;
import it.auties.whatsapp.model.info.MessageInfo;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.jid.JidServer;
import it.auties.whatsapp.model.message.model.ChatMessageKey;
import it.auties.whatsapp.model.message.model.Message;
import it.auties.whatsapp.model.message.model.MessageStatus;
import it.auties.whatsapp.model.message.standard.AudioMessage;
import it.auties.whatsapp.model.message.standard.ContactMessage;
import it.auties.whatsapp.model.message.standard.DocumentMessage;
import it.auties.whatsapp.model.message.standard.ImageMessage;
import it.auties.whatsapp.model.message.standard.LocationMessage;
import it.auties.whatsapp.model.message.standard.ReactionMessage;
import it.auties.whatsapp.model.message.standard.StickerMessage;
import it.auties.whatsapp.model.message.standard.TextMessage;
import it.auties.whatsapp.model.message.standard.VideoOrGifMessage;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Converte mensagens do Cobalt para o JSON do contrato (formato Baileys) entregue em
 * {@code messages.upsert} e {@code messages.update}. Campos ausentes são omitidos, nunca null.
 */
final class CobaltMessageMapper {

    /** Mensagens de controle do protocolo: não viram messages.upsert. */
    private static final Set<Message.Type> IGNORED = Set.of(
            Message.Type.EMPTY, Message.Type.PROTOCOL, Message.Type.SENDER_KEY_DISTRIBUTION,
            Message.Type.DEVICE_SYNC, Message.Type.KEEP_IN_CHAT, Message.Type.STICKER_SYNC);

    private CobaltMessageMapper() {}

    static Optional<Map<String, Object>> upsert(ChatMessageInfo info, UUID instanceId) {
        var container = info.message().unbox();
        if (IGNORED.contains(container.type())) {
            return Optional.empty();
        }
        var content = content(container.content(), container.type());
        var data = new LinkedHashMap<String, Object>();
        data.put("key", key(info.key(), info.senderJid()));
        info.pushName().ifPresent(name -> data.put("pushName", name));
        data.put("status", status(info.status()));
        data.put("message", Map.of(content.type(), content.value()));
        data.put("messageType", content.type());
        data.put("messageTimestamp", info.timestampSeconds().orElse(Instant.now().getEpochSecond()));
        data.put("instanceId", instanceId.toString());
        data.put("source", device(info.id()));
        return Optional.of(data);
    }

    /** messages.update do contrato: identifica a mensagem pela key e traz o novo status. */
    static Optional<Map<String, Object>> statusUpdate(MessageInfo<?> info, UUID instanceId) {
        if (!(info instanceof ChatMessageInfo chat)) {
            return Optional.empty();
        }
        var key = key(chat.key(), chat.senderJid());
        var data = new LinkedHashMap<String, Object>();
        data.put("messageId", chat.id());
        data.put("keyId", chat.id());
        data.put("remoteJid", key.get("remoteJid"));
        data.put("fromMe", key.get("fromMe"));
        if (key.containsKey("participant")) {
            data.put("participant", key.get("participant"));
        }
        data.put("status", status(chat.status()));
        data.put("instanceId", instanceId.toString());
        return Optional.of(data);
    }

    /** Nomes de status do contrato (padrão Baileys). */
    static String status(MessageStatus status) {
        if (status == null) {
            return "PENDING";
        }
        return switch (status) {
            case ERROR -> "ERROR";
            case PENDING -> "PENDING";
            case SERVER_ACK -> "SERVER_ACK";
            case DELIVERED -> "DELIVERY_ACK";
            case READ -> "READ";
            case PLAYED -> "PLAYED";
        };
    }

    private static Map<String, Object> key(ChatMessageKey key, Jid sender) {
        var map = new LinkedHashMap<String, Object>();
        map.put("remoteJid", key.chatJid().toString());
        map.put("fromMe", key.fromMe());
        map.put("id", key.id());
        if (key.chatJid().hasServer(JidServer.groupOrCommunity()) && sender != null) {
            map.put("participant", sender.toSimpleJid().toString());
        }
        return map;
    }

    /** {@code value}: um objeto com os campos, ou uma string no caso de "conversation" (como no Baileys). */
    private record Content(String type, Object value) {}

    private static Content content(Message message, Message.Type type) {
        var fields = new LinkedHashMap<String, Object>();
        return switch (message) {
            // Texto simples sai como "conversation", que é o campo que os clientes leem primeiro
            case TextMessage text -> new Content("conversation", text.text());
            case ImageMessage image -> {
                image.caption().ifPresent(v -> fields.put("caption", v));
                image.mimetype().ifPresent(v -> fields.put("mimetype", v));
                image.mediaUrl().ifPresent(v -> fields.put("url", v));
                yield new Content("imageMessage", fields);
            }
            case VideoOrGifMessage video -> {
                video.caption().ifPresent(v -> fields.put("caption", v));
                video.mimetype().ifPresent(v -> fields.put("mimetype", v));
                seconds(video.duration(), fields);
                if (video.gifPlayback()) {
                    fields.put("gifPlayback", true);
                }
                yield new Content("videoMessage", fields);
            }
            case AudioMessage audio -> {
                audio.mimetype().ifPresent(v -> fields.put("mimetype", v));
                seconds(audio.duration(), fields);
                fields.put("ptt", audio.voiceMessage());
                yield new Content("audioMessage", fields);
            }
            case DocumentMessage document -> {
                document.fileName().ifPresent(v -> fields.put("fileName", v));
                document.title().ifPresent(v -> fields.put("title", v));
                document.caption().ifPresent(v -> fields.put("caption", v));
                document.mimetype().ifPresent(v -> fields.put("mimetype", v));
                yield new Content("documentMessage", fields);
            }
            case StickerMessage sticker -> {
                sticker.mimetype().ifPresent(v -> fields.put("mimetype", v));
                fields.put("isAnimated", sticker.animated());
                yield new Content("stickerMessage", fields);
            }
            case LocationMessage location -> {
                fields.put("degreesLatitude", location.latitude());
                fields.put("degreesLongitude", location.longitude());
                location.name().ifPresent(v -> fields.put("name", v));
                location.address().ifPresent(v -> fields.put("address", v));
                yield new Content("locationMessage", fields);
            }
            case ReactionMessage reaction -> {
                fields.put("text", reaction.content());
                fields.put("key", key(reaction.key(), reaction.key().senderJid().orElse(null)));
                yield new Content("reactionMessage", fields);
            }
            case ContactMessage contact -> {
                fields.put("displayName", contact.name());
                yield new Content("contactMessage", fields);
            }
            default -> new Content(genericName(type), fields);
        };
    }

    private static void seconds(OptionalInt duration, Map<String, Object> fields) {
        duration.ifPresent(v -> fields.put("seconds", v));
    }

    /** POLL_CREATION → pollCreationMessage, como no protobuf do WhatsApp. */
    static String genericName(Message.Type type) {
        var parts = type.name().toLowerCase(Locale.ROOT).split("_");
        var name = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            name.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
        }
        return name.append("Message").toString();
    }

    /** Mesma heurística do getDevice() do Baileys, usada no campo "source". */
    static String device(String messageId) {
        if (messageId == null) {
            return "unknown";
        }
        if (messageId.matches("^3A.{18}$")) {
            return "ios";
        }
        if (messageId.matches("^3E.{20}$")) {
            return "web";
        }
        if (messageId.matches("^(.{21}|.{32})$")) {
            return "android";
        }
        if (messageId.matches("^.{18}$")) {
            return "desktop";
        }
        return "unknown";
    }
}

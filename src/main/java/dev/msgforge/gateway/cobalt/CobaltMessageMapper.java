// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.gateway.cobalt;

import com.github.auties00.cobalt.wire.core.jid.Jid;
import com.github.auties00.cobalt.wire.core.message.MessageInfo;
import com.github.auties00.cobalt.wire.core.message.MessageKey;
import com.github.auties00.cobalt.wire.core.message.MessageStatus;
import com.github.auties00.cobalt.wire.linked.chat.ChatMessageInfo;
import com.github.auties00.cobalt.wire.linked.message.EmptyMessage;
import com.github.auties00.cobalt.wire.linked.message.Message;
import com.github.auties00.cobalt.wire.linked.message.contact.ContactMessage;
import com.github.auties00.cobalt.wire.linked.message.contact.ContactsArrayMessage;
import com.github.auties00.cobalt.wire.linked.message.poll.PollCreationMessage;
import com.github.auties00.cobalt.wire.linked.message.poll.PollUpdateMessage;
import com.github.auties00.cobalt.wire.linked.message.group.SenderKeyDistributionMessage;
import com.github.auties00.cobalt.wire.linked.message.location.LocationMessage;
import com.github.auties00.cobalt.wire.linked.message.media.AudioMessage;
import com.github.auties00.cobalt.wire.linked.message.media.DocumentMessage;
import com.github.auties00.cobalt.wire.linked.message.media.ImageMessage;
import com.github.auties00.cobalt.wire.linked.message.media.MediaMessage;
import com.github.auties00.cobalt.wire.linked.message.media.StickerMessage;
import com.github.auties00.cobalt.wire.linked.message.media.VideoMessage;
import com.github.auties00.cobalt.wire.linked.message.system.DeviceSentMessage;
import com.github.auties00.cobalt.wire.linked.message.system.KeepInChatMessage;
import com.github.auties00.cobalt.wire.linked.message.system.ProtocolMessage;
import com.github.auties00.cobalt.wire.linked.message.system.StickerSyncRMRMessage;
import com.github.auties00.cobalt.wire.linked.message.text.ExtendedTextMessage;
import com.github.auties00.cobalt.wire.linked.message.text.ReactionMessage;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

/**
 * Converte mensagens do Cobalt para o JSON do contrato (formato Baileys) entregue em
 * {@code messages.upsert} e {@code messages.update}. Campos ausentes são omitidos, nunca null.
 */
final class CobaltMessageMapper {

    private CobaltMessageMapper() {}

    /** Como os JIDs saem no JSON sem tradução de LID: o JID de usuário, sem aparelho. */
    static final Function<Jid, String> PLAIN =
            jid -> jid.hasUserServer() ? jid.toUserJid().toString() : jid.toString();

    static Optional<Map<String, Object>> upsert(ChatMessageInfo info, UUID instanceId) {
        return upsert(info, instanceId, PLAIN);
    }

    static Optional<Map<String, Object>> upsert(ChatMessageInfo info, UUID instanceId, Function<Jid, String> names) {
        var message = info.message().content();
        if (ignored(message)) {
            return Optional.empty();
        }
        var content = content(message, names);
        var data = new LinkedHashMap<String, Object>();
        data.put("key", key(info.key(), info.senderJid().orElse(null), names));
        info.pushName().ifPresent(name -> data.put("pushName", name));
        data.put("status", status(info.status().orElse(null)));
        data.put("message", Map.of(content.type(), content.value()));
        data.put("messageType", content.type());
        data.put("messageTimestamp", info.timestamp().map(Instant::getEpochSecond)
                .orElse(Instant.now().getEpochSecond()));
        data.put("instanceId", instanceId.toString());
        data.put("source", device(info.key().id().orElse(null)));
        return Optional.of(data);
    }

    /** Mensagens de controle do protocolo: não viram messages.upsert. */
    static boolean ignored(Message message) {
        return switch (message) {
            case EmptyMessage _, ProtocolMessage _, SenderKeyDistributionMessage _, DeviceSentMessage _,
                 KeepInChatMessage _, StickerSyncRMRMessage _ -> true;
            default -> false;
        };
    }

    /** messages.update do contrato: identifica a mensagem pela key e traz o novo status. */
    static Optional<Map<String, Object>> statusUpdate(MessageInfo info, UUID instanceId) {
        return statusUpdate(info, instanceId, PLAIN);
    }

    static Optional<Map<String, Object>> statusUpdate(MessageInfo info, UUID instanceId,
                                                      Function<Jid, String> names) {
        if (!(info instanceof ChatMessageInfo chat)) {
            return Optional.empty();
        }
        var key = key(chat.key(), chat.senderJid().orElse(null), names);
        var id = chat.key().id().orElse(null);
        var data = new LinkedHashMap<String, Object>();
        data.put("messageId", id);
        data.put("keyId", id);
        data.put("remoteJid", key.get("remoteJid"));
        data.put("fromMe", key.get("fromMe"));
        if (key.containsKey("participant")) {
            data.put("participant", key.get("participant"));
        }
        data.put("status", status(chat.status().orElse(null)));
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

    private static Map<String, Object> key(MessageKey key, Jid sender, Function<Jid, String> names) {
        var map = new LinkedHashMap<String, Object>();
        var chat = key.parentJid().orElse(null);
        map.put("remoteJid", chat == null ? null : names.apply(chat));
        map.put("fromMe", key.fromMe());
        map.put("id", key.id().orElse(null));
        var participant = sender != null ? sender : key.senderJid().orElse(null);
        if (chat != null && chat.hasGroupOrCommunityServer() && participant != null) {
            map.put("participant", names.apply(participant));
        }
        return map;
    }

    /** {@code value}: um objeto com os campos, ou uma string no caso de "conversation" (como no Baileys). */
    private record Content(String type, Object value) {}

    private static Content content(Message message, Function<Jid, String> names) {
        var fields = new LinkedHashMap<String, Object>();
        return switch (message) {
            // Texto sai como "conversation", que é o campo que os clientes leem primeiro
            case ExtendedTextMessage text -> new Content("conversation", text.text().orElse(""));
            case ImageMessage image -> {
                image.caption().ifPresent(v -> fields.put("caption", v));
                media(image, fields);
                image.width().ifPresent(v -> fields.put("width", v));
                image.height().ifPresent(v -> fields.put("height", v));
                yield new Content("imageMessage", fields);
            }
            case VideoMessage video -> {
                video.caption().ifPresent(v -> fields.put("caption", v));
                media(video, fields);
                seconds(video.seconds(), fields);
                video.width().ifPresent(v -> fields.put("width", v));
                video.height().ifPresent(v -> fields.put("height", v));
                if (video.gifPlayback()) {
                    fields.put("gifPlayback", true);
                }
                yield new Content("videoMessage", fields);
            }
            case AudioMessage audio -> {
                media(audio, fields);
                seconds(audio.seconds(), fields);
                fields.put("ptt", audio.ptt());
                yield new Content("audioMessage", fields);
            }
            case DocumentMessage document -> {
                document.fileName().ifPresent(v -> fields.put("fileName", v));
                document.title().ifPresent(v -> fields.put("title", v));
                document.caption().ifPresent(v -> fields.put("caption", v));
                media(document, fields);
                yield new Content("documentMessage", fields);
            }
            case StickerMessage sticker -> {
                media(sticker, fields);
                fields.put("isAnimated", sticker.isAnimated());
                sticker.width().ifPresent(v -> fields.put("width", v));
                sticker.height().ifPresent(v -> fields.put("height", v));
                yield new Content("stickerMessage", fields);
            }
            case LocationMessage location -> {
                location.degreesLatitude().ifPresent(v -> fields.put("degreesLatitude", v));
                location.degreesLongitude().ifPresent(v -> fields.put("degreesLongitude", v));
                location.name().ifPresent(v -> fields.put("name", v));
                location.address().ifPresent(v -> fields.put("address", v));
                yield new Content("locationMessage", fields);
            }
            case ReactionMessage reaction -> {
                fields.put("text", reaction.text().orElse(""));
                reaction.key().ifPresent(key -> fields.put("key", key(key, null, names)));
                yield new Content("reactionMessage", fields);
            }
            case ContactMessage contact -> {
                fields.putAll(contactFields(contact));
                yield new Content("contactMessage", fields);
            }
            case ContactsArrayMessage array -> {
                array.displayName().ifPresent(v -> fields.put("displayName", v));
                fields.put("contacts", array.contacts().stream().map(CobaltMessageMapper::contactFields).toList());
                yield new Content("contactsArrayMessage", fields);
            }
            case PollCreationMessage poll -> {
                poll.name().ifPresent(v -> fields.put("name", v));
                fields.put("options", poll.options().stream()
                        .map(o -> o.optionName().<Map<String, Object>>map(n -> Map.of("optionName", n)).orElse(Map.of()))
                        .toList());
                poll.selectableOptionsCount().ifPresent(v -> fields.put("selectableOptionsCount", v));
                yield new Content("pollCreationMessage", fields);
            }
            case PollUpdateMessage vote -> {
                // Voto: a chave da enquete, o voto cifrado (como chega do WhatsApp) e, quando o motor
                // conseguiu decifrar (a enquete está no Store dele), as opções escolhidas pelo nome.
                vote.pollCreationMessageKey().ifPresent(key -> fields.put("pollCreationMessageKey", key(key, null, names)));
                vote.vote().ifPresent(enc -> {
                    var encrypted = new LinkedHashMap<String, Object>();
                    enc.encPayload().ifPresent(v -> encrypted.put("encPayload", Base64.getEncoder().encodeToString(v)));
                    enc.encIv().ifPresent(v -> encrypted.put("encIv", Base64.getEncoder().encodeToString(v)));
                    fields.put("vote", encrypted);
                });
                fields.put("selectedOptions", List.copyOf(vote.selectedOptions()));
                yield new Content("pollUpdateMessage", fields);
            }
            default -> new Content(genericName(message), fields);
        };
    }

    /** Os campos de mídia de uma mensagem (recebida, ou enviada depois do upload), em ordem. */
    static Map<String, Object> media(MediaMessage media) {
        var fields = new LinkedHashMap<String, Object>();
        media(media, fields);
        return fields;
    }

    /**
     * Os campos de mídia do contrato, com os nomes do messages.upsert padrão: {@code url},
     * {@code mimetype}, {@code fileSha256}, {@code fileLength} (bytes), {@code mediaKey},
     * {@code fileEncSha256}, {@code directPath} e {@code mediaKeyTimestamp} (segundos). Bytes em
     * base64. Com eles a mídia pode ser baixada depois, sem a mensagem na memória do motor.
     */
    private static void media(MediaMessage media, Map<String, Object> fields) {
        media.mediaUrl().ifPresent(v -> fields.put("url", v));
        media.mimetype().ifPresent(v -> fields.put("mimetype", v));
        media.mediaSha256().ifPresent(v -> fields.put("fileSha256", BASE64.encodeToString(v)));
        media.mediaSize().ifPresent(v -> fields.put("fileLength", v));
        media.mediaKey().ifPresent(v -> fields.put("mediaKey", BASE64.encodeToString(v)));
        media.mediaEncryptedSha256().ifPresent(v -> fields.put("fileEncSha256", BASE64.encodeToString(v)));
        media.mediaDirectPath().ifPresent(v -> fields.put("directPath", v));
        media.mediaKeyTimestamp().ifPresent(v -> fields.put("mediaKeyTimestamp", v.getEpochSecond()));
    }

    private static final java.util.Base64.Encoder BASE64 = java.util.Base64.getEncoder();

    private static void seconds(OptionalInt duration, Map<String, Object> fields) {
        duration.ifPresent(v -> fields.put("seconds", v));
    }

    /** PollCreationMessage → pollCreationMessage, como no protobuf do WhatsApp. */
    static String genericName(Message message) {
        var name = message.getClass().getSimpleName();
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
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

    /** Nome e vCard do cartão de contato, como no contrato ({@code displayName}, {@code vcard}). */
    static Map<String, Object> contactFields(ContactMessage contact) {
        var fields = new LinkedHashMap<String, Object>();
        contact.displayName().ifPresent(v -> fields.put("displayName", v));
        contact.vcard().ifPresent(v -> fields.put("vcard", v));
        return fields;
    }
}

package dev.msgforge.event;

import dev.msgforge.event.GatewayEvent.ApplicationStartup;
import dev.msgforge.event.GatewayEvent.CallReceived;
import dev.msgforge.event.GatewayEvent.ChatsDeleted;
import dev.msgforge.event.GatewayEvent.ChatsUpdated;
import dev.msgforge.event.GatewayEvent.ChatsUpserted;
import dev.msgforge.event.GatewayEvent.ConnectionUpdated;
import dev.msgforge.event.GatewayEvent.Contact;
import dev.msgforge.event.GatewayEvent.ContactsUpdated;
import dev.msgforge.event.GatewayEvent.ContactsUpserted;
import dev.msgforge.event.GatewayEvent.GroupParticipantsUpdated;
import dev.msgforge.event.GatewayEvent.GroupUpdated;
import dev.msgforge.event.GatewayEvent.GroupsUpserted;
import dev.msgforge.event.GatewayEvent.MessageDeleted;
import dev.msgforge.event.GatewayEvent.MessageReceived;
import dev.msgforge.event.GatewayEvent.MessageSent;
import dev.msgforge.event.GatewayEvent.MessageStatusUpdated;
import dev.msgforge.event.GatewayEvent.MessagesSynced;
import dev.msgforge.event.GatewayEvent.PresenceUpdated;
import dev.msgforge.event.GatewayEvent.QrCodeUpdated;
import dev.msgforge.gateway.WhatsAppGateway.GroupInfo;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O {@code data} de cada evento, no formato do contrato (JSON do Baileys, ver docs/eventos.md).
 * Campos sem valor são omitidos, exceto onde o contrato traz null explícito.
 */
final class EventPayloads {

    private EventPayloads() {}

    /** @param instanceId id da instância (vai em alguns payloads); null no APPLICATION_STARTUP */
    static Object dataOf(GatewayEvent event, String instanceId) {
        return switch (event) {
            case ApplicationStartup _ -> Map.of("status", "started");
            case QrCodeUpdated q -> {
                var qr = new LinkedHashMap<String, Object>();
                qr.put("instance", q.instanceName());
                put(qr, "code", q.code());
                put(qr, "pairingCode", q.pairingCode());
                put(qr, "base64", q.base64());
                yield Map.of("qrcode", qr);
            }
            case ConnectionUpdated c -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("instance", c.instanceName());
                map.put("state", c.state().value());
                put(map, "statusReason", c.statusReason());
                put(map, "wuid", c.wuid());
                put(map, "profileName", c.profileName());
                yield map;
            }
            case MessageSent m -> Map.of(
                    "key", Map.of("remoteJid", m.message().remoteJid(), "fromMe", true, "id", m.message().id()),
                    "messageTimestamp", m.message().timestamp(),
                    "status", "PENDING");
            case MessageReceived r -> r.data();
            case MessageStatusUpdated s -> s.data();
            case MessagesSynced s -> s.messages();
            case MessageDeleted d -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("remoteJid", d.remoteJid());
                map.put("fromMe", d.fromMe());
                map.put("id", d.id());
                put(map, "participant", d.participant());
                map.put("status", "DELETED");
                yield map;
            }
            case ContactsUpserted c -> contacts(c.contacts(), instanceId);
            case ContactsUpdated c -> contacts(c.contacts(), instanceId);
            case PresenceUpdated p -> {
                var presence = new LinkedHashMap<String, Object>();
                presence.put("lastKnownPresence", p.presence());
                put(presence, "lastSeen", p.lastSeen());
                var map = new LinkedHashMap<String, Object>();
                map.put("id", p.chatJid());
                map.put("presences", Map.of(p.participantJid(), presence));
                yield map;
            }
            case ChatsUpserted c -> c.chats().stream().map(chat -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("remoteJid", chat.jid());
                map.put("instanceId", instanceId);
                put(map, "name", chat.name());
                map.put("unreadMessages", chat.unreadMessages() == null ? 0 : chat.unreadMessages());
                return map;
            }).toList();
            case ChatsUpdated c -> c.jids().stream().map(jid -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("remoteJid", jid);
                map.put("instanceId", instanceId);
                return map;
            }).toList();
            case ChatsDeleted c -> c.jids();
            case GroupsUpserted g -> g.groups().stream().map(EventPayloads::group).toList();
            case GroupUpdated g -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("id", g.groupJid());
                put(map, "author", g.author());
                put(map, "subject", g.subject());
                put(map, "desc", g.desc());
                put(map, "announce", g.announce());
                put(map, "restrict", g.restrict());
                yield List.of(map);
            }
            case GroupParticipantsUpdated g -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("id", g.groupJid());
                put(map, "author", g.author());
                map.put("participants", g.participants());
                map.put("action", g.action());
                // Além dos JIDs, os dados de cada participante, com o número quando o JID o traz
                map.put("participantsData", g.participants().stream().map(jid -> {
                    var data = new LinkedHashMap<String, Object>();
                    data.put("jid", jid);
                    put(data, "phoneNumber", phoneNumber(jid));
                    return data;
                }).toList());
                yield map;
            }
            case CallReceived c -> {
                var map = new LinkedHashMap<String, Object>();
                map.put("id", c.id());
                map.put("from", c.from());
                map.put("chatId", c.chatId());
                map.put("isGroup", c.groupJid() != null);
                put(map, "groupJid", c.groupJid());
                map.put("isVideo", c.isVideo());
                map.put("status", c.status());
                map.put("offline", c.offline());
                map.put("date", Instant.ofEpochMilli(c.timestamp()).toString());
                yield map;
            }
        };
    }

    private static List<Map<String, Object>> contacts(List<Contact> contacts, String instanceId) {
        return contacts.stream().map(contact -> {
            var map = new LinkedHashMap<String, Object>();
            map.put("remoteJid", contact.jid());
            map.put("pushName", contact.pushName() != null ? contact.pushName() : user(contact.jid()));
            map.put("profilePicUrl", null);
            map.put("instanceId", instanceId);
            return (Map<String, Object>) map;
        }).toList();
    }

    /** GroupMetadata do Baileys. */
    static Map<String, Object> group(GroupInfo group) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", group.id());
        map.put("subject", group.subject());
        map.put("subjectOwner", group.subjectOwner());
        map.put("subjectTime", group.subjectTime());
        map.put("size", group.participants().size());
        map.put("creation", group.creation());
        map.put("owner", group.owner());
        map.put("desc", group.desc());
        map.put("restrict", group.restrict());
        map.put("announce", group.announce());
        map.put("participants", group.participants().stream().map(p -> {
            var participant = new LinkedHashMap<String, Object>();
            participant.put("id", p.id());
            participant.put("admin", p.admin());
            return participant;
        }).toList());
        return map;
    }

    /** Número do JID de usuário ({@code 5561...@s.whatsapp.net}); null para LID e outros servidores. */
    static String phoneNumber(String jid) {
        return jid != null && jid.endsWith("@s.whatsapp.net") ? user(jid) : null;
    }

    private static String user(String jid) {
        var at = jid.indexOf('@');
        return at < 0 ? jid : jid.substring(0, at);
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}

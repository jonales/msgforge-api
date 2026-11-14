package dev.msgforge.gateway.cobalt;

import com.github.auties00.cobalt.stanza.model.Stanza;
import com.github.auties00.cobalt.wire.core.jid.Jid;
import com.github.auties00.cobalt.wire.linked.call.CallEndReason;
import com.github.auties00.cobalt.wire.linked.call.IncomingCall;
import com.github.auties00.cobalt.wire.linked.chat.ChatMessageInfo;
import com.github.auties00.cobalt.wire.linked.contact.Contact;
import com.github.auties00.cobalt.wire.linked.contact.ContactStatus;
import com.github.auties00.cobalt.wire.linked.message.system.ProtocolMessage;
import com.github.auties00.cobalt.wire.linked.sync.action.SyncAction;
import com.github.auties00.cobalt.wire.linked.sync.action.chat.ArchiveChatAction;
import com.github.auties00.cobalt.wire.linked.sync.action.chat.ClearChatAction;
import com.github.auties00.cobalt.wire.linked.sync.action.chat.DeleteChatAction;
import com.github.auties00.cobalt.wire.linked.sync.action.chat.LockChatAction;
import com.github.auties00.cobalt.wire.linked.sync.action.chat.MarkChatAsReadAction;
import com.github.auties00.cobalt.wire.linked.sync.action.chat.MuteAction;
import com.github.auties00.cobalt.wire.linked.sync.action.contact.ContactAction;
import com.github.auties00.cobalt.wire.linked.sync.action.contact.PinAction;
import dev.msgforge.event.GatewayEvent;
import dev.msgforge.event.GatewayEvent.CallReceived;
import dev.msgforge.event.GatewayEvent.ChatsDeleted;
import dev.msgforge.event.GatewayEvent.ChatsUpdated;
import dev.msgforge.event.GatewayEvent.ContactsUpdated;
import dev.msgforge.event.GatewayEvent.GroupParticipantsUpdated;
import dev.msgforge.event.GatewayEvent.GroupUpdated;
import dev.msgforge.event.GatewayEvent.GroupsUpserted;
import dev.msgforge.event.GatewayEvent.MessageDeleted;
import dev.msgforge.event.GatewayEvent.PresenceUpdated;
import dev.msgforge.gateway.WhatsAppGateway.GroupInfo;
import dev.msgforge.gateway.WhatsAppGateway.GroupParticipant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * Converte os callbacks do Cobalt (contatos, conversas, presença, grupos, chamadas, mensagem
 * apagada) em {@link GatewayEvent} neutros. O formato do {@code data} do contrato fica no
 * {@code EventPayloads}; aqui só se extraem os fatos, com os JIDs já traduzidos (LID → número
 * quando o motor conhece).
 */
final class CobaltEvents {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CobaltEvents() {}

    /**
     * Mensagem apagada para todos: o WhatsApp manda um ProtocolMessage REVOKE com a key da original.
     * Como no Baileys, a key do evento é a da mensagem que apagou (conversa, fromMe e participante)
     * com o id da mensagem apagada.
     */
    static Optional<MessageDeleted> revoke(String instanceName, ChatMessageInfo info, Function<Jid, String> names) {
        if (!(info.message().content() instanceof ProtocolMessage protocol)
                || protocol.type().orElse(null) != ProtocolMessage.Type.REVOKE) {
            return Optional.empty();
        }
        var revokedId = protocol.key().flatMap(key -> key.id()).orElse(null);
        var chat = info.key().parentJid().orElse(null);
        if (revokedId == null || chat == null) {
            return Optional.empty();
        }
        var sender = info.senderJid().or(() -> info.key().senderJid()).orElse(null);
        var participant = chat.hasGroupOrCommunityServer() && sender != null ? names.apply(sender) : null;
        return Optional.of(new MessageDeleted(instanceName, names.apply(chat), info.key().fromMe(), revokedId,
                participant));
    }

    /** Nome do contato: o da agenda, o completo ou o curto, nessa ordem; null quando não há. */
    static GatewayEvent.Contact contact(Contact contact, Function<Jid, String> names) {
        var name = contact.chosenName().or(contact::fullName).or(contact::shortName).orElse(null);
        return new GatewayEvent.Contact(names.apply(contact.jid()), name);
    }

    /** Nomes de presença do Baileys. */
    static String presence(ContactStatus status) {
        if (status == null) {
            return "unavailable";
        }
        return switch (status) {
            case AVAILABLE -> "available";
            case UNAVAILABLE -> "unavailable";
            case COMPOSING -> "composing";
            case RECORDING -> "recording";
        };
    }

    static PresenceUpdated presence(String instanceName, Jid chat, Jid participant, Contact contact,
                                    Function<Jid, String> names) {
        var status = contact == null ? null : contact.lastKnownPresence();
        var lastSeen = contact == null ? null : contact.lastSeen().map(Instant::getEpochSecond).orElse(null);
        return new PresenceUpdated(instanceName, names.apply(chat), names.apply(participant), presence(status), lastSeen);
    }

    /**
     * Ação de app state (sincronizada entre os aparelhos da conta): arquivar, silenciar, fixar,
     * marcar como lida e trancar viram CHATS_UPDATE; apagar a conversa, CHATS_DELETE; o nome do
     * contato na agenda, CONTACTS_UPDATE.
     *
     * @param index índice da mutação, um array JSON com o nome da ação e o JID ({@code ["archive","...@s.whatsapp.net"]})
     */
    static Optional<GatewayEvent> appState(String instanceName, SyncAction<?> action, String index,
                                           Function<Jid, String> names) {
        var jid = indexJid(index).map(names).orElse(null);
        if (jid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(switch (action) {
            case ArchiveChatAction _, MuteAction _, PinAction _, MarkChatAsReadAction _, LockChatAction _,
                 ClearChatAction _ -> new ChatsUpdated(instanceName, List.of(jid));
            case DeleteChatAction _ -> new ChatsDeleted(instanceName, List.of(jid));
            case ContactAction contact -> new ContactsUpdated(instanceName, List.of(new GatewayEvent.Contact(jid,
                    contact.fullName().or(contact::firstName).orElse(null))));
            default -> null;
        });
    }

    /** O JID do índice (segundo item do array). */
    static Optional<Jid> indexJid(String index) {
        if (index == null || index.isBlank()) {
            return Optional.empty();
        }
        try {
            var array = JSON.readTree(index);
            if (!array.isArray() || array.size() < 2 || !array.get(1).isString()) {
                return Optional.empty();
            }
            var jid = Jid.of(array.get(1).asString());
            return jid.hasUserServer() || jid.hasLidServer() || jid.hasGroupOrCommunityServer()
                    ? Optional.of(jid) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Chamada recebida (tocando). */
    static CallReceived offer(String instanceName, IncomingCall call, Function<Jid, String> names) {
        return new CallReceived(instanceName, call.callId(), names.apply(call.peer()), names.apply(call.chatJid()),
                call.groupJid().map(names).orElse(null), call.videoOffered(), "offer", call.offlineOffer(),
                call.timestamp().toEpochMilli());
    }

    /**
     * Fim da chamada. {@code offer} é a oferta guardada quando tocou (traz conversa, grupo e vídeo);
     * null quando o processo não a viu.
     */
    static CallReceived ended(String instanceName, String callId, Jid from, CallEndReason reason, CallReceived offer,
                              Function<Jid, String> names) {
        var status = switch (reason == null ? CallEndReason.UNKNOWN : reason) {
            case TIMEOUT -> "timeout";
            case REJECTED, REJECTED_ELSEWHERE, REJECT_DO_NOT_DISTURB, REJECT_BLOCKED -> "reject";
            case ACCEPTED_ELSEWHERE -> "accept";
            default -> "terminate";
        };
        var fromJid = from != null ? names.apply(from) : offer != null ? offer.from() : null;
        return new CallReceived(instanceName, callId, fromJid, offer != null ? offer.chatId() : fromJid,
                offer != null ? offer.groupJid() : null, offer != null && offer.isVideo(), status, false,
                Instant.now().toEpochMilli());
    }

    /**
     * Notificação {@code w:gp2} (mudanças em grupos), como o WhatsApp manda a todos os participantes.
     * O Cobalt aplica a notificação no Store, mas não avisa os listeners; os eventos saem daqui.
     *
     * <p>Filhos tratados: {@code create} (GROUPS_UPSERT); {@code add}, {@code remove},
     * {@code promote}, {@code demote}, {@code modify} (GROUP_PARTICIPANTS_UPDATE); {@code subject},
     * {@code description}, {@code locked}/{@code unlocked}, {@code announcement}/{@code not_announcement}
     * (GROUP_UPDATE). Os demais são ignorados.
     */
    static List<GatewayEvent> groupNotification(String instanceName, Stanza node, LidResolver lids) {
        if (!node.hasDescription("notification") || !"w:gp2".equals(node.getAttributeAsString("type", null))) {
            return List.of();
        }
        var group = node.getAttributeAsString("from").flatMap(CobaltEvents::jid)
                .filter(Jid::hasGroupOrCommunityServer).orElse(null);
        if (group == null) {
            return List.of();
        }
        var groupJid = group.toString();
        var author = node.getAttributeAsString("participant").flatMap(CobaltEvents::jid).map(lids).orElse(null);
        var events = new ArrayList<GatewayEvent>();
        for (var action : node.children()) {
            switch (action.description()) {
                case "create" -> events.add(new GroupsUpserted(instanceName,
                        List.of(created(group, action.getChild("group").orElse(action), lids))));
                case "add", "remove", "promote", "demote", "modify" -> {
                    var participants = participants(action, lids).stream().map(GroupParticipant::id).toList();
                    if (!participants.isEmpty()) {
                        events.add(new GroupParticipantsUpdated(instanceName, groupJid, author, participants,
                                action.description()));
                    }
                }
                case "subject" -> action.getAttributeAsString("subject").ifPresent(subject -> events.add(
                        new GroupUpdated(instanceName, groupJid,
                                action.getAttributeAsString("s_o").flatMap(CobaltEvents::jid).map(lids).orElse(author),
                                subject, null, null, null)));
                case "description" -> {
                    // Descrição apagada: sai vazia (null seria "não mudou")
                    var desc = action.hasChild("delete") ? "" : body(action);
                    if (desc != null) {
                        events.add(new GroupUpdated(instanceName, groupJid, author, null, desc, null, null));
                    }
                }
                case "locked" -> events.add(new GroupUpdated(instanceName, groupJid, author, null, null, null, true));
                case "unlocked" -> events.add(new GroupUpdated(instanceName, groupJid, author, null, null, null, false));
                case "announcement" -> events.add(new GroupUpdated(instanceName, groupJid, author, null, null, true, null));
                case "not_announcement" ->
                        events.add(new GroupUpdated(instanceName, groupJid, author, null, null, false, null));
                default -> { }
            }
        }
        return events;
    }

    /** Grupo do {@code <create>}: dados no {@code <group>} e participantes como filhos. */
    private static GroupInfo created(Jid group, Stanza node, LidResolver lids) {
        var creator = node.getAttributeAsString("creator").flatMap(CobaltEvents::jid).map(lids).orElse(null);
        var subjectOwner = node.getAttributeAsString("s_o").flatMap(CobaltEvents::jid).map(lids).orElse(creator);
        var creation = node.getAttributeAsLong("creation", 0L);
        return new GroupInfo(group.toString(), node.getAttributeAsString("subject", null), subjectOwner,
                node.getAttributeAsLong("s_t", creation), creation, creator,
                node.getChild("description").map(CobaltEvents::body).orElse(null),
                node.hasChild("announcement"), node.hasChild("locked"), participants(node, lids));
    }

    /**
     * Participantes ({@code <participant jid type phone_number>}). Em grupos com LID, o
     * {@code phone_number} ensina o número ao resolvedor antes da tradução.
     */
    private static List<GroupParticipant> participants(Stanza action, LidResolver lids) {
        var participants = new ArrayList<GroupParticipant>();
        for (var child : action.getChildren("participant")) {
            var jid = child.getAttributeAsString("jid").flatMap(CobaltEvents::jid).orElse(null);
            if (jid == null) {
                continue;
            }
            child.getAttributeAsString("phone_number").flatMap(CobaltEvents::jid)
                    .ifPresent(phone -> lids.learn(jid, phone));
            var admin = switch (child.getAttributeAsString("type", "")) {
                case "superadmin" -> "superadmin";
                case "admin" -> "admin";
                default -> null;
            };
            participants.add(new GroupParticipant(lids.apply(jid), admin));
        }
        return participants;
    }

    private static String body(Stanza description) {
        return description.getChild("body").flatMap(Stanza::toContentString).orElse(null);
    }

    private static Optional<Jid> jid(String value) {
        try {
            return Optional.of(Jid.of(value));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}

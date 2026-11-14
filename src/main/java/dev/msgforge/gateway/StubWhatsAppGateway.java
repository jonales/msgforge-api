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
        // O stub não recebe mensagens nem chamadas: as configurações e o proxy só aparecem no log
        log.info("[stub] connect {} -> QR gerado (settings={}, proxy={})", instance.getName(),
                instance.getSettings(), instance.getProxy().map(Object::toString).orElse("nenhum"));
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
    public java.util.Map<String, String> checkNumbers(Instance instance, java.util.List<String> jids) {
        var result = new java.util.LinkedHashMap<String, String>();
        jids.forEach(jid -> result.put(jid, jid));
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

    // ---- Grupos: guardados em memória, por instância, para o fluxo criar → consultar → alterar

    private final java.util.concurrent.ConcurrentHashMap<String, GroupInfo> groups = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, String> invites = new java.util.concurrent.ConcurrentHashMap<>();

    private static String key(Instance instance, String groupJid) {
        return instance.getName() + "|" + groupJid;
    }

    private GroupInfo existing(Instance instance, String groupJid) {
        var group = groups.get(key(instance, groupJid));
        if (group == null) {
            throw new dev.msgforge.common.ApiExceptionHandler.NotFoundException("Group not found: " + groupJid);
        }
        return group;
    }

    private static String self(Instance instance) {
        return instance.getOwnerJid() != null ? instance.getOwnerJid() : "5500000000000@s.whatsapp.net";
    }

    @Override
    public GroupInfo createGroup(Instance instance, String subject, String description, java.util.List<String> participants) {
        var jid = "1203630" + String.format("%011d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000_000L)) + "@g.us";
        var now = Instant.now().getEpochSecond();
        var members = new java.util.ArrayList<GroupParticipant>();
        members.add(new GroupParticipant(self(instance), "superadmin"));
        participants.stream().distinct().filter(p -> !p.equals(self(instance)))
                .forEach(p -> members.add(new GroupParticipant(p, null)));
        var group = new GroupInfo(jid, subject, self(instance), now, now, self(instance), description, false, false,
                java.util.List.copyOf(members));
        groups.put(key(instance, jid), group);
        log.info("[stub] grupo criado {} -> {} ({} participantes)", instance.getName(), jid, members.size());
        events.publishEvent(new GatewayEvent.GroupsUpserted(instance.getName(), java.util.List.of(group)));
        return group;
    }

    @Override
    public GroupInfo groupInfo(Instance instance, String groupJid) {
        return existing(instance, groupJid);
    }

    @Override
    public java.util.List<GroupInfo> groups(Instance instance) {
        var prefix = instance.getName() + "|";
        return groups.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                .map(java.util.Map.Entry::getValue)
                .sorted(java.util.Comparator.comparingLong(GroupInfo::creation).thenComparing(GroupInfo::id))
                .toList();
    }

    @Override
    public String groupInviteCode(Instance instance, String groupJid) {
        existing(instance, groupJid);
        return invites.computeIfAbsent(key(instance, groupJid), k -> newInviteCode());
    }

    @Override
    public String revokeGroupInvite(Instance instance, String groupJid) {
        existing(instance, groupJid);
        var code = newInviteCode();
        invites.put(key(instance, groupJid), code);
        return code;
    }

    private static String newInviteCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 22);
    }

    @Override
    public String acceptGroupInvite(Instance instance, String inviteCode) {
        return invites.entrySet().stream()
                .filter(e -> e.getValue().equals(inviteCode))
                .map(e -> e.getKey().substring(e.getKey().indexOf('|') + 1))
                .findFirst()
                .orElseThrow(() -> new dev.msgforge.common.ApiExceptionHandler.BadRequestException("Invalid invite code"));
    }

    @Override
    public java.util.List<String> updateParticipants(Instance instance, String groupJid, ParticipantAction action,
                                                     java.util.List<String> participants) {
        var done = new java.util.ArrayList<String>();
        groups.compute(key(instance, groupJid), (k, group) -> {
            if (group == null) {
                throw new dev.msgforge.common.ApiExceptionHandler.NotFoundException("Group not found: " + groupJid);
            }
            var members = new java.util.LinkedHashMap<String, GroupParticipant>();
            group.participants().forEach(p -> members.put(p.id(), p));
            for (var jid : participants) {
                var current = members.get(jid);
                var changed = switch (action) {
                    case ADD -> current == null && members.put(jid, new GroupParticipant(jid, null)) == null;
                    case REMOVE -> current != null && !"superadmin".equals(current.admin()) && members.remove(jid) != null;
                    case PROMOTE -> current != null && current.admin() == null
                            && members.put(jid, new GroupParticipant(jid, "admin")) != null;
                    case DEMOTE -> current != null && "admin".equals(current.admin())
                            && members.put(jid, new GroupParticipant(jid, null)) != null;
                };
                if (changed) {
                    done.add(jid);
                }
            }
            return new GroupInfo(group.id(), group.subject(), group.subjectOwner(), group.subjectTime(), group.creation(),
                    group.owner(), group.desc(), group.announce(), group.restrict(), java.util.List.copyOf(members.values()));
        });
        if (!done.isEmpty()) {
            events.publishEvent(new GatewayEvent.GroupParticipantsUpdated(instance.getName(), groupJid, self(instance),
                    java.util.List.copyOf(done), action.name().toLowerCase(java.util.Locale.ROOT)));
        }
        return done;
    }

    @Override
    public void updateGroup(Instance instance, String groupJid, GroupUpdate update) {
        groups.compute(key(instance, groupJid), (k, g) -> {
            if (g == null) {
                throw new dev.msgforge.common.ApiExceptionHandler.NotFoundException("Group not found: " + groupJid);
            }
            return switch (update) {
                case GroupSubject su -> new GroupInfo(g.id(), su.subject(), self(instance), Instant.now().getEpochSecond(),
                        g.creation(), g.owner(), g.desc(), g.announce(), g.restrict(), g.participants());
                case GroupDescription d -> new GroupInfo(g.id(), g.subject(), g.subjectOwner(), g.subjectTime(),
                        g.creation(), g.owner(), d.description(), g.announce(), g.restrict(), g.participants());
                case GroupPicture _ -> g;
                case GroupSetting set -> set.setting() == Setting.SEND_MESSAGES
                        ? new GroupInfo(g.id(), g.subject(), g.subjectOwner(), g.subjectTime(), g.creation(), g.owner(),
                                g.desc(), set.adminsOnly(), g.restrict(), g.participants())
                        : new GroupInfo(g.id(), g.subject(), g.subjectOwner(), g.subjectTime(), g.creation(), g.owner(),
                                g.desc(), g.announce(), set.adminsOnly(), g.participants());
            };
        });
        var author = self(instance);
        var name = instance.getName();
        var event = switch (update) {
            case GroupSubject su -> new GatewayEvent.GroupUpdated(name, groupJid, author, su.subject(), null, null, null);
            case GroupDescription d -> new GatewayEvent.GroupUpdated(name, groupJid, author, null, d.description(), null, null);
            case GroupPicture _ -> null;   // foto não vira groups.update (como no WhatsApp)
            case GroupSetting set -> set.setting() == Setting.SEND_MESSAGES
                    ? new GatewayEvent.GroupUpdated(name, groupJid, author, null, null, set.adminsOnly(), null)
                    : new GatewayEvent.GroupUpdated(name, groupJid, author, null, null, null, set.adminsOnly());
        };
        if (event != null) {
            events.publishEvent(event);
        }
    }

    @Override
    public void leaveGroup(Instance instance, String groupJid) {
        if (groups.remove(key(instance, groupJid)) == null) {
            throw new dev.msgforge.common.ApiExceptionHandler.NotFoundException("Group not found: " + groupJid);
        }
        invites.remove(key(instance, groupJid));
        // Como no WhatsApp: sair do grupo chega como a remoção da própria conta
        events.publishEvent(new GatewayEvent.GroupParticipantsUpdated(instance.getName(), groupJid, self(instance),
                java.util.List.of(self(instance)), "remove"));
    }

    private SentMessage sent(Instance instance, String jid) {
        var id = "3EB0" + HexFormat.of().withUpperCase().formatHex(UUID.randomUUID().toString().getBytes(), 0, 8);
        var message = new SentMessage(id, jid, Instant.now().getEpochSecond());
        events.publishEvent(new GatewayEvent.MessageSent(instance.getName(), message));
        return message;
    }
}

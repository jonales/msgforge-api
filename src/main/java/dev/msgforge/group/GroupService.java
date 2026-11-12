package dev.msgforge.group;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.gateway.Jids;
import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.gateway.WhatsAppGateway.GroupInfo;
import dev.msgforge.gateway.WhatsAppGateway.GroupUpdate;
import dev.msgforge.gateway.WhatsAppGateway.ParticipantAction;
import dev.msgforge.instance.Instance;
import dev.msgforge.instance.InstanceService;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Grupos (/group/*). Valida JIDs aqui; o gateway recebe tudo já normalizado. */
@Service
public class GroupService {

    static final String INVITE_URL = "https://chat.whatsapp.com/";

    private final InstanceService instances;
    private final WhatsAppGateway gateway;

    public GroupService(InstanceService instances, WhatsAppGateway gateway) {
        this.instances = instances;
        this.gateway = gateway;
    }

    public record Result<T>(Instance instance, T value) {}

    public GroupInfo create(String instanceName, String subject, String description, List<String> participants) {
        var instance = instances.get(instanceName);
        return gateway.createGroup(instance, subject.strip(), blankToNull(description), participantJids(participants));
    }

    /** Com a foto (consulta extra ao WhatsApp). */
    public Result<GroupInfo> info(String instanceName, String groupJid) {
        var instance = instances.get(instanceName);
        var jid = groupJid(groupJid);
        return new Result<>(instance, gateway.groupInfo(instance, jid));
    }

    public String pictureUrl(Instance instance, String groupJid) {
        return gateway.profilePictureUrl(instance, groupJid);
    }

    public List<GroupInfo> all(String instanceName) {
        return gateway.groups(instances.get(instanceName));
    }

    public String inviteCode(String instanceName, String groupJid) {
        return gateway.groupInviteCode(instances.get(instanceName), groupJid(groupJid));
    }

    public String revokeInvite(String instanceName, String groupJid) {
        return gateway.revokeGroupInvite(instances.get(instanceName), groupJid(groupJid));
    }

    /** Aceita o código ou o link inteiro (https://chat.whatsapp.com/{código}). */
    public String acceptInvite(String instanceName, String inviteCode) {
        var code = inviteCode == null ? "" : inviteCode.strip();
        if (code.startsWith(INVITE_URL)) {
            code = code.substring(INVITE_URL.length());
        }
        if (!code.matches("[A-Za-z0-9]{10,64}")) {
            throw new BadRequestException("Invalid invite code");
        }
        return gateway.acceptGroupInvite(instances.get(instanceName), code);
    }

    /** Devolve o status de cada participante pedido, na ordem pedida. */
    public List<GroupDtos.ParticipantStatus> updateParticipants(String instanceName, String groupJid, String action,
                                                                List<String> participants) {
        var instance = instances.get(instanceName);
        var jids = participantJids(participants);
        var done = new HashSet<>(gateway.updateParticipants(instance, groupJid(groupJid),
                ParticipantAction.valueOf(action.toUpperCase(Locale.ROOT)), jids));
        return jids.stream().map(jid -> new GroupDtos.ParticipantStatus(jid, done.contains(jid) ? "200" : "400")).toList();
    }

    public void update(String instanceName, String groupJid, GroupUpdate update) {
        gateway.updateGroup(instances.get(instanceName), groupJid(groupJid), update);
    }

    public static WhatsAppGateway.GroupSetting setting(String action) {
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "announcement" -> new WhatsAppGateway.GroupSetting(WhatsAppGateway.Setting.SEND_MESSAGES, true);
            case "not_announcement" -> new WhatsAppGateway.GroupSetting(WhatsAppGateway.Setting.SEND_MESSAGES, false);
            case "locked" -> new WhatsAppGateway.GroupSetting(WhatsAppGateway.Setting.EDIT_INFO, true);
            case "unlocked" -> new WhatsAppGateway.GroupSetting(WhatsAppGateway.Setting.EDIT_INFO, false);
            default -> throw new BadRequestException("Invalid action: \"" + action + "\"");
        };
    }

    public String leave(String instanceName, String groupJid) {
        var jid = groupJid(groupJid);
        gateway.leaveGroup(instances.get(instanceName), jid);
        return jid;
    }

    /** {@code groupJid} da query string: precisa ser um grupo. */
    static String groupJid(String value) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("groupJid is required");
        }
        var jid = Jids.toJid(value);
        if (!jid.endsWith("@g.us")) {
            throw new BadRequestException("Invalid group: \"" + value + "\"");
        }
        return jid;
    }

    /** Participantes: telefones ou JIDs de usuário, sem repetição. */
    static List<String> participantJids(List<String> participants) {
        return participants.stream().map(p -> {
            var jid = Jids.toJid(p);
            if (!jid.endsWith("@s.whatsapp.net") && !jid.endsWith("@lid")) {
                throw new BadRequestException("Invalid participant: \"" + p + "\"");
            }
            return jid;
        }).distinct().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

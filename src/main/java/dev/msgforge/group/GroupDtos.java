package dev.msgforge.group;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.msgforge.gateway.WhatsAppGateway.GroupInfo;
import dev.msgforge.gateway.WhatsAppGateway.GroupParticipant;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Corpos e respostas de /group/* no formato do contrato da API. */
public final class GroupDtos {

    private GroupDtos() {}

    /** Limites do próprio WhatsApp: assunto até 100 caracteres, descrição até 2048. */
    public record CreateGroupRequest(
            @NotBlank @Size(max = 100) String subject,
            @Size(max = 2048) String description,
            @NotEmpty @Size(max = 1024) List<@NotBlank String> participants) {}

    public record SubjectRequest(@NotBlank @Size(max = 100) String subject) {}

    public record DescriptionRequest(@NotBlank @Size(max = 2048) String description) {}

    /** {@code image}: URL http(s) ou base64 de um JPEG ou PNG. */
    public record PictureRequest(@NotBlank String image) {}

    public record UpdateParticipantRequest(
            @NotBlank @Pattern(regexp = "(?i)add|remove|promote|demote") String action,
            @NotEmpty @Size(max = 1024) List<@NotBlank String> participants) {}

    public record UpdateSettingRequest(
            @NotBlank @Pattern(regexp = "(?i)announcement|not_announcement|locked|unlocked") String action) {}

    /** Participante no formato do contrato: {@code admin} é null, "admin" ou "superadmin". */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Participant(String id, String admin) {
        static Participant of(GroupParticipant p) {
            return new Participant(p.id(), p.admin());
        }
    }

    /** {@code participants} só aparece quando pedido; {@code pictureUrl} só é consultado no findGroupInfos. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record GroupResponse(
            String id, String subject, String subjectOwner, long subjectTime,
            String pictureUrl,
            int size, long creation, String owner, String desc, boolean restrict, boolean announce,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<Participant> participants) {

        static GroupResponse of(GroupInfo group, boolean withParticipants, String pictureUrl) {
            return new GroupResponse(group.id(), group.subject(), group.subjectOwner(), group.subjectTime(),
                    pictureUrl, group.participants().size(), group.creation(), group.owner(), group.desc(),
                    group.restrict(), group.announce(),
                    withParticipants ? group.participants().stream().map(Participant::of).toList() : null);
        }
    }

    public record ParticipantsResponse(List<Participant> participants) {}

    public record InviteCodeResponse(String inviteUrl, String inviteCode) {}

    public record RevokeResponse(boolean revoked, String inviteCode) {}

    public record AcceptResponse(boolean accepted, String groupJid) {}

    /** {@code status}: "200" para quem a ação valeu; "400" para quem não (já estava, não é admin...). */
    public record ParticipantStatus(String jid, String status) {}

    public record UpdateParticipantsResponse(List<ParticipantStatus> updateParticipants) {}

    public record UpdateResponse(String update) {}

    public record LeaveResponse(String groupJid, boolean leave) {}
}

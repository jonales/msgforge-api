package dev.msgforge.group;

import dev.msgforge.gateway.WhatsAppGateway.GroupDescription;
import dev.msgforge.gateway.WhatsAppGateway.GroupPicture;
import dev.msgforge.gateway.WhatsAppGateway.GroupSubject;
import dev.msgforge.group.GroupDtos.AcceptResponse;
import dev.msgforge.group.GroupDtos.CreateGroupRequest;
import dev.msgforge.group.GroupDtos.DescriptionRequest;
import dev.msgforge.group.GroupDtos.GroupResponse;
import dev.msgforge.group.GroupDtos.InviteCodeResponse;
import dev.msgforge.group.GroupDtos.LeaveResponse;
import dev.msgforge.group.GroupDtos.Participant;
import dev.msgforge.group.GroupDtos.ParticipantsResponse;
import dev.msgforge.group.GroupDtos.PictureRequest;
import dev.msgforge.group.GroupDtos.RevokeResponse;
import dev.msgforge.group.GroupDtos.SubjectRequest;
import dev.msgforge.group.GroupDtos.UpdateParticipantRequest;
import dev.msgforge.group.GroupDtos.UpdateParticipantsResponse;
import dev.msgforge.group.GroupDtos.UpdateResponse;
import dev.msgforge.group.GroupDtos.UpdateSettingRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/group")
public class GroupController {

    private static final UpdateResponse SUCCESS = new UpdateResponse("success");

    private final GroupService groups;

    public GroupController(GroupService groups) {
        this.groups = groups;
    }

    @PostMapping("/create/{instance}")
    @ResponseStatus(HttpStatus.CREATED)
    public GroupResponse create(@PathVariable String instance, @Valid @RequestBody CreateGroupRequest body) {
        var group = groups.create(instance, body.subject(), body.description(), body.participants());
        return GroupResponse.of(group, true, null);
    }

    @GetMapping("/findGroupInfos/{instance}")
    public GroupResponse findGroupInfos(@PathVariable String instance,
                                        @RequestParam(required = false) String groupJid) {
        var found = groups.info(instance, groupJid);
        var group = found.value();
        return GroupResponse.of(group, true, groups.pictureUrl(found.instance(), group.id()));
    }

    @GetMapping("/fetchAllGroups/{instance}")
    public List<GroupResponse> fetchAllGroups(@PathVariable String instance,
                                              @RequestParam(defaultValue = "false") boolean getParticipants) {
        return groups.all(instance).stream().map(g -> GroupResponse.of(g, getParticipants, null)).toList();
    }

    @GetMapping("/participants/{instance}")
    public ParticipantsResponse participants(@PathVariable String instance,
                                             @RequestParam(required = false) String groupJid) {
        var group = groups.info(instance, groupJid).value();
        return new ParticipantsResponse(group.participants().stream().map(Participant::of).toList());
    }

    @GetMapping("/inviteCode/{instance}")
    public InviteCodeResponse inviteCode(@PathVariable String instance,
                                         @RequestParam(required = false) String groupJid) {
        var code = groups.inviteCode(instance, groupJid);
        return new InviteCodeResponse(GroupService.INVITE_URL + code, code);
    }

    @PostMapping("/revokeInviteCode/{instance}")
    @ResponseStatus(HttpStatus.CREATED)
    public RevokeResponse revokeInviteCode(@PathVariable String instance,
                                           @RequestParam(required = false) String groupJid) {
        return new RevokeResponse(true, groups.revokeInvite(instance, groupJid));
    }

    @GetMapping("/acceptInviteCode/{instance}")
    public AcceptResponse acceptInviteCode(@PathVariable String instance,
                                           @RequestParam(required = false) String inviteCode) {
        return new AcceptResponse(true, groups.acceptInvite(instance, inviteCode));
    }

    @PostMapping("/updateParticipant/{instance}")
    @ResponseStatus(HttpStatus.CREATED)
    public UpdateParticipantsResponse updateParticipant(@PathVariable String instance,
                                                        @RequestParam(required = false) String groupJid,
                                                        @Valid @RequestBody UpdateParticipantRequest body) {
        return new UpdateParticipantsResponse(
                groups.updateParticipants(instance, groupJid, body.action(), body.participants()));
    }

    @PostMapping("/updateSetting/{instance}")
    @ResponseStatus(HttpStatus.CREATED)
    public UpdateResponse updateSetting(@PathVariable String instance, @RequestParam(required = false) String groupJid,
                                        @Valid @RequestBody UpdateSettingRequest body) {
        groups.update(instance, groupJid, GroupService.setting(body.action()));
        return SUCCESS;
    }

    @PostMapping("/updateGroupSubject/{instance}")
    @ResponseStatus(HttpStatus.CREATED)
    public UpdateResponse updateGroupSubject(@PathVariable String instance, @RequestParam(required = false) String groupJid,
                                             @Valid @RequestBody SubjectRequest body) {
        groups.update(instance, groupJid, new GroupSubject(body.subject().strip()));
        return SUCCESS;
    }

    @PostMapping("/updateGroupDescription/{instance}")
    @ResponseStatus(HttpStatus.CREATED)
    public UpdateResponse updateGroupDescription(@PathVariable String instance,
                                                 @RequestParam(required = false) String groupJid,
                                                 @Valid @RequestBody DescriptionRequest body) {
        groups.update(instance, groupJid, new GroupDescription(body.description()));
        return SUCCESS;
    }

    @PostMapping("/updateGroupPicture/{instance}")
    @ResponseStatus(HttpStatus.CREATED)
    public UpdateResponse updateGroupPicture(@PathVariable String instance, @RequestParam(required = false) String groupJid,
                                             @Valid @RequestBody PictureRequest body) {
        groups.update(instance, groupJid, new GroupPicture(body.image()));
        return SUCCESS;
    }

    @DeleteMapping("/leaveGroup/{instance}")
    public LeaveResponse leaveGroup(@PathVariable String instance, @RequestParam(required = false) String groupJid) {
        return new LeaveResponse(groups.leave(instance, groupJid), true);
    }
}

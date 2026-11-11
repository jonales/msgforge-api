package dev.msgforge.chat;

import dev.msgforge.chat.ChatDtos.FetchProfileRequest;
import dev.msgforge.chat.ChatDtos.MarkReadRequest;
import dev.msgforge.chat.ChatDtos.NumberRequest;
import dev.msgforge.chat.ChatDtos.NumberStatus;
import dev.msgforge.chat.ChatDtos.PresenceResponse;
import dev.msgforge.chat.ChatDtos.ProfilePictureResponse;
import dev.msgforge.chat.ChatDtos.ProfileResponse;
import dev.msgforge.chat.ChatDtos.ReadResponse;
import dev.msgforge.chat.ChatDtos.SendPresenceRequest;
import dev.msgforge.chat.ChatDtos.UpdateProfileNameRequest;
import dev.msgforge.chat.ChatDtos.UpdateProfilePictureRequest;
import dev.msgforge.chat.ChatDtos.UpdateProfileStatusRequest;
import dev.msgforge.chat.ChatDtos.UpdateResponse;
import dev.msgforge.gateway.WhatsAppGateway.ProfileName;
import dev.msgforge.gateway.WhatsAppGateway.ProfilePicture;
import dev.msgforge.gateway.WhatsAppGateway.ProfileStatus;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final UpdateResponse SUCCESS = new UpdateResponse("success");

    private final ChatService chats;

    public ChatController(ChatService chats) {
        this.chats = chats;
    }

    @PostMapping("/whatsappNumbers/{instance}")
    public List<NumberStatus> whatsappNumbers(@PathVariable String instance,
                                              @Valid @RequestBody ChatDtos.WhatsappNumbersRequest body) {
        return chats.whatsappNumbers(instance, body.numbers());
    }

    @PostMapping("/markMessageAsRead/{instance}")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadResponse markMessageAsRead(@PathVariable String instance, @Valid @RequestBody MarkReadRequest body) {
        chats.markAsRead(instance, body.readMessages());
        return new ReadResponse("Read messages", "success");
    }

    @PostMapping("/sendPresence/{instance}")
    @ResponseStatus(HttpStatus.CREATED)
    public PresenceResponse sendPresence(@PathVariable String instance, @Valid @RequestBody SendPresenceRequest body) {
        var presence = chats.sendPresence(instance, body.number(), body.presence(), body.delay());
        return new PresenceResponse(presence.name().toLowerCase(Locale.ROOT));
    }

    @PostMapping("/fetchProfilePictureUrl/{instance}")
    public ProfilePictureResponse fetchProfilePictureUrl(@PathVariable String instance,
                                                         @Valid @RequestBody NumberRequest body) {
        return chats.profilePictureUrl(instance, body.number());
    }

    @PostMapping("/fetchProfile/{instance}")
    public ProfileResponse fetchProfile(@PathVariable String instance,
                                        @RequestBody(required = false) FetchProfileRequest body) {
        var profile = chats.fetchProfile(instance, body == null ? null : body.number());
        return new ProfileResponse(profile.jid(), profile.name(), profile.exists(), profile.pictureUrl(),
                profile.status());
    }

    @PostMapping("/updateProfileName/{instance}")
    public UpdateResponse updateProfileName(@PathVariable String instance,
                                            @Valid @RequestBody UpdateProfileNameRequest body) {
        chats.updateProfile(instance, new ProfileName(body.name().strip()));
        return SUCCESS;
    }

    @PostMapping("/updateProfileStatus/{instance}")
    public UpdateResponse updateProfileStatus(@PathVariable String instance,
                                              @Valid @RequestBody UpdateProfileStatusRequest body) {
        chats.updateProfile(instance, new ProfileStatus(body.status()));
        return SUCCESS;
    }

    @PostMapping("/updateProfilePicture/{instance}")
    public UpdateResponse updateProfilePicture(@PathVariable String instance,
                                               @Valid @RequestBody UpdateProfilePictureRequest body) {
        chats.updateProfile(instance, new ProfilePicture(body.picture()));
        return SUCCESS;
    }
}

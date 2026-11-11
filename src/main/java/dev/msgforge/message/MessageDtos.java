package dev.msgforge.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;

/** Corpos de /message/* no formato da Evolution API v2. */
public final class MessageDtos {

    private MessageDtos() {}

    public record SendTextRequest(
            @NotBlank String number,
            @NotBlank String text,
            Integer delay,
            Boolean linkPreview,
            Boolean mentionsEveryOne,
            List<String> mentioned,
            Map<String, Object> quoted) {}

    public record SendMediaRequest(
            @NotBlank String number,
            @NotBlank @Pattern(regexp = "image|video|document|audio") String mediatype,
            String mimetype,
            String caption,
            @NotBlank String media,
            String fileName,
            Integer delay) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MessageResponse(
            Key key,
            String pushName,
            String status,
            Map<String, Object> message,
            String messageType,
            long messageTimestamp,
            String instanceId,
            String source) {

        public record Key(String remoteJid, boolean fromMe, String id) {}
    }
}

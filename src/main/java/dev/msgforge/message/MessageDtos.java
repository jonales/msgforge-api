package dev.msgforge.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** Corpos de /message/* no formato do contrato da API. */
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

    /**
     * Áudio de voz (PTT): {@code audio} em URL http(s) ou base64, em qualquer formato que o ffmpeg
     * leia. {@code encoding} (padrão true) converte para OGG/Opus; false envia como veio.
     */
    public record SendWhatsAppAudioRequest(
            @NotBlank String number,
            @NotBlank String audio,
            Integer delay,
            Boolean encoding) {}

    public record SendLocationRequest(
            @NotBlank String number,
            String name,
            String address,
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            Integer delay) {}

    /** Um ou mais contatos (cartões de visita). */
    public record SendContactRequest(
            @NotBlank String number,
            @NotEmpty @Size(max = 50) List<@Valid @NotNull ContactEntry> contact,
            Integer delay) {}

    public record ContactEntry(
            @NotBlank @Size(max = 200) String fullName,
            String wuid,
            @NotBlank @Size(max = 30) String phoneNumber,
            @Size(max = 200) String organization,
            @Size(max = 200) String email,
            @Size(max = 500) String url) {}

    /** Reação a uma mensagem; {@code reaction} vazio remove a reação. */
    public record SendReactionRequest(
            @NotNull @Valid ReactionKey key,
            @NotNull @Size(max = 20) String reaction) {}

    public record ReactionKey(
            @NotBlank String remoteJid,
            Boolean fromMe,
            @NotBlank String id) {}

    /** Enquete: {@code selectableCount} 1 = escolha única; 0 ou omitido = várias. */
    public record SendPollRequest(
            @NotBlank String number,
            @NotBlank @Size(max = 255) String name,
            @Min(0) Integer selectableCount,
            @NotNull @Size(min = 2, max = 12) List<@NotBlank @Size(max = 100) String> values,
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

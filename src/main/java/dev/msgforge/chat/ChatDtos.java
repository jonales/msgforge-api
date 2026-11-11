package dev.msgforge.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Corpos de /chat/* no formato do contrato da API. */
public final class ChatDtos {

    private ChatDtos() {}

    public record WhatsappNumbersRequest(@NotEmpty @Size(max = 100) List<@NotBlank String> numbers) {}

    /** {@code jid} nulo quando o número nem tem formato de telefone. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record NumberStatus(boolean exists, String jid, String number) {}

    public record ReadMessage(@NotBlank String remoteJid, Boolean fromMe, @NotBlank String id) {}

    public record MarkReadRequest(@NotEmpty @Size(max = 500) List<@Valid ReadMessage> readMessages) {}

    public record ReadResponse(String message, String read) {}

    public record SendPresenceRequest(
            @NotBlank String number,
            @NotBlank @Pattern(regexp = "(?i)available|unavailable|composing|recording|paused") String presence,
            Integer delay) {}

    public record PresenceResponse(String presence) {}

    public record NumberRequest(@NotBlank String number) {}

    /** {@code number} vazio: o perfil da própria conta. */
    public record FetchProfileRequest(String number) {}

    public record ProfilePictureResponse(String wuid, String profilePictureUrl) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ProfileResponse(String wuid, String name, boolean numberExists, String picture, String status) {}

    /** Limites do próprio WhatsApp: nome até 25 caracteres, recado até 139. */
    public record UpdateProfileNameRequest(@NotBlank @Size(max = 25) String name) {}

    public record UpdateProfileStatusRequest(@NotBlank @Size(max = 139) String status) {}

    /** {@code picture}: URL http(s) ou base64 de um JPEG ou PNG. */
    public record UpdateProfilePictureRequest(@NotBlank String picture) {}

    public record UpdateResponse(String update) {}
}

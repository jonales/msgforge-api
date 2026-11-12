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

    // ---- Histórico (ADR 0008)

    public record KeyFilter(String remoteJid, Boolean fromMe, String id) {}

    public record Where(@Valid KeyFilter key) {}

    /** {@code offset}: tamanho da página (padrão 50, até 500), como no contrato. {@code page} começa em 1. */
    public record FindMessagesRequest(@Valid Where where,
                                      @jakarta.validation.constraints.Min(1) Integer page,
                                      @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(500) Integer offset) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordKey(String remoteJid, boolean fromMe, String id, String participant) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MessageRecord(String id, RecordKey key, String pushName, String messageType,
                                java.util.Map<String, Object> message, long messageTimestamp, String instanceId,
                                String status) {

        public static MessageRecord of(dev.msgforge.history.MessageHistory.Stored m) {
            return new MessageRecord(m.id().toString(),
                    new RecordKey(m.key().remoteJid(), m.key().fromMe(), m.key().id(), m.key().participant()),
                    m.pushName(), m.messageType(), m.message(), m.messageTimestamp(), m.instanceId().toString(),
                    m.status());
        }
    }

    public record MessagesPage(long total, long pages, int currentPage, List<MessageRecord> records) {}

    public record FindMessagesResponse(MessagesPage messages) {}

    /** {@code updatedAt}: horário da última mensagem (ISO-8601). */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ChatSummary(String remoteJid, String pushName, String updatedAt, MessageRecord lastMessage) {}
}

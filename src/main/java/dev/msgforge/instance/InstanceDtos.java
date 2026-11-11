package dev.msgforge.instance;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.msgforge.webhook.WebhookDtos.WebhookConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** DTOs de /instance no formato JSON do contrato da API. */
public final class InstanceDtos {

    private InstanceDtos() {}

    public record CreateInstanceRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,100}$") String instanceName,
            String token,
            Boolean qrcode,
            String number,
            String integration,
            WebhookConfig webhook) {

        public String integrationOrDefault() {
            return integration == null || integration.isBlank() ? "WHATSAPP-BAILEYS" : integration;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateInstanceResponse(InstanceInfo instance, String hash, QrCode qrcode) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InstanceInfo(String instanceName, String instanceId, String integration, String status) {}

    /** Resposta de /instance/connect: pairingCode, code (texto do QR) e base64 (imagem). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QrCode(String pairingCode, String code, String base64, Integer count) {}

    public record ConnectionStateResponse(StateInfo instance) {
        public record StateInfo(String instanceName, ConnectionState state) {}
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FetchedInstance(
            String id,
            String name,
            ConnectionState connectionStatus,
            String ownerJid,
            String profileName,
            String integration,
            String number,
            String token,
            String createdAt,
            String updatedAt) {

        static FetchedInstance of(Instance i) {
            return new FetchedInstance(
                    i.getId().toString(), i.getName(), i.getConnectionState(), i.getOwnerJid(),
                    i.getProfileName(), i.getIntegration(), i.getNumber(), i.getToken(),
                    i.getCreatedAt().toString(), i.getUpdatedAt().toString());
        }
    }

    public record SimpleResponse(String status, boolean error, Object response) {
        public static SimpleResponse ok(String message) {
            return new SimpleResponse("SUCCESS", false, java.util.Map.of("message", message));
        }
    }
}

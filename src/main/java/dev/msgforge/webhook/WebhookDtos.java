package dev.msgforge.webhook;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public final class WebhookDtos {

    private WebhookDtos() {}

    /** Corpo de POST /webhook/set/{instance}: { "webhook": { ... } } */
    public record SetWebhookRequest(@NotNull @Valid WebhookConfig webhook) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WebhookConfig(
            Boolean enabled,
            @NotBlank String url,
            Map<String, String> headers,
            Boolean byEvents,
            Boolean base64,
            List<String> events) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WebhookResponse(
            String id,
            String url,
            Boolean enabled,
            Boolean webhookByEvents,
            Boolean webhookBase64,
            List<String> events,
            Map<String, String> headers) {}
}

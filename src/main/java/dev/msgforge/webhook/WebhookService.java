package dev.msgforge.webhook;

import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.instance.Instance;
import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.webhook.WebhookDtos.WebhookConfig;
import dev.msgforge.webhook.WebhookDtos.WebhookResponse;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
public class WebhookService {

    private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {};

    private final WebhookRepository webhooks;
    private final InstanceRepository instances;
    private final JsonMapper json;

    public WebhookService(WebhookRepository webhooks, InstanceRepository instances, JsonMapper json) {
        this.webhooks = webhooks;
        this.instances = instances;
        this.json = json;
    }

    @Transactional
    public WebhookResponse set(String instanceName, WebhookConfig config) {
        var instance = instance(instanceName);
        return toResponse(save(instance, config));
    }

    @Transactional
    public Webhook save(Instance instance, WebhookConfig config) {
        var webhook = webhooks.findByInstanceId(instance.getId()).orElseGet(() -> new Webhook(instance.getId()));
        var headers = config.headers() == null || config.headers().isEmpty() ? null : json.writeValueAsString(config.headers());
        webhook.update(
                config.url(),
                config.enabled() == null || config.enabled(),
                Boolean.TRUE.equals(config.byEvents()),
                Boolean.TRUE.equals(config.base64()),
                config.events(),
                headers);
        return webhooks.save(webhook);
    }

    @Transactional(readOnly = true)
    public Optional<WebhookResponse> find(String instanceName) {
        return webhooks.findByInstanceId(instance(instanceName).getId()).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Optional<Webhook> findEntity(Instance instance) {
        return webhooks.findByInstanceId(instance.getId());
    }

    public Map<String, String> headersOf(Webhook webhook) {
        return webhook.getHeaders() == null ? Map.of() : json.readValue(webhook.getHeaders(), HEADERS);
    }

    private Instance instance(String name) {
        return instances.findByName(name)
                .orElseThrow(() -> new NotFoundException("The \"" + name + "\" instance does not exist"));
    }

    private WebhookResponse toResponse(Webhook w) {
        return new WebhookResponse(w.getId().toString(), w.getUrl(), w.isEnabled(), w.isByEvents(),
                w.isBase64(), w.getEvents(), headersOf(w));
    }
}

package dev.msgforge.webhook;

import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.common.ApiExceptionHandler.NotFoundException;
import dev.msgforge.event.EventType;
import dev.msgforge.instance.Instance;
import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.security.PrivateNetworkGuard;
import dev.msgforge.webhook.WebhookDtos.WebhookConfig;
import dev.msgforge.webhook.WebhookDtos.WebhookResponse;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
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
    private final PrivateNetworkGuard guard;

    public WebhookService(WebhookRepository webhooks, InstanceRepository instances, JsonMapper json,
                          PrivateNetworkGuard guard) {
        this.webhooks = webhooks;
        this.instances = instances;
        this.json = json;
        this.guard = guard;
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
                validUrl(config.url()),
                config.enabled() == null || config.enabled(),
                Boolean.TRUE.equals(config.byEvents()),
                Boolean.TRUE.equals(config.base64()),
                validEvents(config.events()),
                headers);
        return webhooks.save(webhook);
    }

    private String validUrl(String url) {
        var normalized = validHttpUrl(url);
        guard.check(normalized).ifPresent(reason -> {
            throw new BadRequestException("Webhook url not allowed: " + reason);
        });
        return normalized;
    }

    private static String validHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BadRequestException("webhook url is required");
        }
        try {
            var uri = URI.create(url.trim());
            if (("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null) {
                return uri.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // cai no erro abaixo
        }
        throw new BadRequestException("Invalid webhook url: \"" + url + "\"");
    }

    /** Aceita os nomes da Evolution (MESSAGES_UPSERT) em qualquer caixa; rejeita os desconhecidos. */
    private static List<String> validEvents(List<String> events) {
        if (events == null) {
            return List.of();
        }
        var known = Arrays.stream(EventType.values()).map(Enum::name).collect(Collectors.toSet());
        var normalized = events.stream()
                .filter(Objects::nonNull)
                .map(e -> e.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        var unknown = normalized.stream().filter(e -> !known.contains(e)).toList();
        if (!unknown.isEmpty()) {
            throw new BadRequestException("Invalid webhook events: " + unknown);
        }
        return normalized;
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

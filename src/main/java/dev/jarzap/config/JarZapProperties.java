package dev.jarzap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jarzap")
public record JarZapProperties(
        String serverUrl,
        Authentication authentication,
        Webhook webhook,
        RabbitMq rabbitmq,
        String gateway) {

    public record Authentication(String apiKey) {}

    public record Webhook(String globalUrl, boolean globalEnabled) {}

    public record RabbitMq(boolean enabled, String exchange) {}
}

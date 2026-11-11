package dev.msgforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "msgforge")
public record MsgforgeProperties(
        String serverUrl,
        Authentication authentication,
        Webhook webhook,
        RabbitMq rabbitmq,
        String gateway) {

    public record Authentication(String apiKey) {}

    public record Webhook(String globalUrl, boolean globalEnabled) {}

    public record RabbitMq(boolean enabled, String exchange) {}
}

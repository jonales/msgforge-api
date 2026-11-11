package dev.msgforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "msgforge")
public record MsgforgeProperties(
        String serverUrl,
        Authentication authentication,
        Webhook webhook,
        RabbitMq rabbitmq,
        String gateway) {

    public record Authentication(String apiKey) {}

    /**
     * Webhooks: global (opcional) + política de entrega usada por todos os webhooks.
     *
     * @param requestTimeoutMs timeout de conexão e de resposta de cada tentativa
     * @param retry            novas tentativas em erro de rede, 408, 429 e 5xx
     */
    public record Webhook(
            String globalUrl,
            boolean globalEnabled,
            @DefaultValue("10000") long requestTimeoutMs,
            @DefaultValue Retry retry) {}

    /**
     * @param maxAttempts    total de tentativas (1 = sem retry)
     * @param initialDelayMs espera antes da 2ª tentativa; dobra a cada falha
     * @param maxDelayMs     teto da espera entre tentativas
     */
    public record Retry(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("1000") long initialDelayMs,
            @DefaultValue("30000") long maxDelayMs) {}

    public record RabbitMq(boolean enabled, String exchange) {}
}

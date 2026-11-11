package dev.msgforge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "msgforge")
public record MsgforgeProperties(
        String serverUrl,
        Authentication authentication,
        Webhook webhook,
        RabbitMq rabbitmq,
        String gateway,
        @DefaultValue Cobalt cobalt) {

    public record Authentication(String apiKey) {}

    /**
     * Webhooks: global (opcional) + política de entrega usada por todos os webhooks.
     *
     * @param requestTimeoutMs timeout de conexão e de resposta de cada tentativa
     * @param retry            novas tentativas em erro de rede, 408, 429 e 5xx
     * @param maxPendingPerInstance entregas aguardando por instância; acima disso o evento é
     *                              descartado (com log), para limitar a memória
     * @param blockPrivateNetworks recusa webhooks de instância que resolvem para IP privado,
     *                             loopback ou link-local (proteção contra SSRF)
     */
    public record Webhook(
            String globalUrl,
            boolean globalEnabled,
            @DefaultValue("10000") long requestTimeoutMs,
            @DefaultValue Retry retry,
            @DefaultValue("1000") int maxPendingPerInstance,
            @DefaultValue("false") boolean blockPrivateNetworks) {}

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

    /**
     * Motor Cobalt ({@code MSGFORGE_GATEWAY=cobalt}).
     *
     * @param deviceName     nome do aparelho em "Aparelhos conectados" (CONFIG_SESSION_PHONE_CLIENT, como na Evolution)
     * @param qrWaitMs       quanto o /instance/connect espera pelo primeiro QR ou pairing code
     * @param sendTimeoutMs  limite para o WhatsApp confirmar um envio
     * @param mediaMaxBytes  tamanho máximo de mídia enviada (URL ou base64)
     * @param proxy          proxy para a conexão com o WhatsApp (http://, https:// ou socks5://); vazio = direto
     */
    public record Cobalt(
            @DefaultValue("Msgforge") String deviceName,
            @DefaultValue("10000") long qrWaitMs,
            @DefaultValue("30000") long sendTimeoutMs,
            @DefaultValue("67108864") long mediaMaxBytes,
            String proxy) {}
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.bot;

import dev.msgforge.security.PrivateNetworkGuard;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chamada a um modelo compatível com a API da OpenAI ({@code POST {base}/chat/completions}). Sem
 * redirecionamento, com tempo limite, e o endereço passa pelo {@link PrivateNetworkGuard} salvo com
 * {@code BOT_ALLOW_PRIVATE_NETWORKS=true}. Os erros não carregam a chave nem o corpo da resposta.
 */
@Component
public class ChatModelClient {

    /** Uma fala da conversa: {@code role} é system, user ou assistant. */
    public record Turn(String role, String content) {}

    public static class ModelException extends RuntimeException {
        public ModelException(String message) {
            super(message);
        }
    }

    private final BotProperties properties;
    private final JsonMapper json;
    private final HttpClient http;

    public ChatModelClient(BotProperties properties, JsonMapper json) {
        this.properties = properties;
        this.json = json;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** A resposta do modelo, sem espaços nas pontas; {@link ModelException} se não veio uma. */
    public String complete(String baseUrl, String apiKey, String model, List<Turn> turns) {
        var url = endpoint(baseUrl);
        if (!properties.allowPrivateNetworks()) {
            new PrivateNetworkGuard(true).inspect(url).ifPresent(problem -> {
                throw new ModelException("endereço do modelo recusado: " + problem);
            });
        }
        var messages = new ArrayList<Map<String, String>>();
        turns.forEach(t -> messages.add(Map.of("role", t.role(), "content", t.content())));
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(
                        Map.of("model", model, "messages", messages, "stream", false))));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new ModelException("falha de rede ao chamar o modelo (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("chamada ao modelo interrompida");
        }
        if (response.statusCode() / 100 != 2) {
            throw new ModelException("o modelo respondeu HTTP " + response.statusCode());
        }
        try {
            var content = json.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (!content.isString() || content.asString().isBlank()) {
                throw new ModelException("resposta do modelo sem texto");
            }
            return content.asString().strip();
        } catch (ModelException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ModelException("resposta do modelo em formato inesperado");
        }
    }

    /** {@code https://api.openai.com/v1} → {@code .../v1/chat/completions}; só http e https. */
    static String endpoint(String baseUrl) {
        var base = baseUrl == null ? "" : baseUrl.strip();
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new ModelException("o endereço do modelo precisa começar com http:// ou https://");
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
    }
}

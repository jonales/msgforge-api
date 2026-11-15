// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.msgforge.bot.ChatModelClient.ModelException;
import dev.msgforge.bot.ChatModelClient.Turn;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ChatModelClientTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String serve(int status, String body, AtomicReference<String> request, AtomicReference<String> auth)
            throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
    }

    @Test
    void endpointAcceptsTheBaseOrTheFullPathAndOnlyHttp() {
        assertThat(ChatModelClient.endpoint("https://api.openai.com/v1/")).isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(ChatModelClient.endpoint("http://x:11434/v1/chat/completions")).isEqualTo("http://x:11434/v1/chat/completions");
        assertThatThrownBy(() -> ChatModelClient.endpoint("file:///etc/passwd")).isInstanceOf(ModelException.class);
        assertThatThrownBy(() -> ChatModelClient.endpoint("")).isInstanceOf(ModelException.class);
    }

    @Test
    void sendsTheConversationAndReadsTheReply() throws Exception {
        var request = new AtomicReference<String>();
        var auth = new AtomicReference<String>();
        var base = serve(200, """
                {"choices":[{"message":{"role":"assistant","content":"  Olá! Como posso ajudar?  "}}]}""", request, auth);
        var client = new ChatModelClient(new BotProperties(true, true, 5000, 0), JsonMapper.builder().build());
        var reply = client.complete(base, "sk-teste", "modelo-x",
                List.of(new Turn("system", "Seja breve"), new Turn("user", "oi")));
        assertThat(reply).isEqualTo("Olá! Como posso ajudar?");
        assertThat(auth.get()).isEqualTo("Bearer sk-teste");
        assertThat(request.get()).contains("\"model\":\"modelo-x\"").contains("\"role\":\"system\"")
                .contains("\"content\":\"oi\"").contains("\"stream\":false");
    }

    @Test
    void errorsNeverCarryTheKeyOrTheBody() throws Exception {
        var base = serve(401, "{\"error\":\"chave sk-teste inválida\"}", new AtomicReference<>(), new AtomicReference<>());
        var client = new ChatModelClient(new BotProperties(true, true, 5000, 0), JsonMapper.builder().build());
        assertThatThrownBy(() -> client.complete(base, "sk-teste", "m", List.of(new Turn("user", "oi"))))
                .isInstanceOf(ModelException.class).hasMessage("o modelo respondeu HTTP 401");
    }

    @Test
    void privateAddressesAreRefusedUnlessAllowed() throws Exception {
        var base = serve(200, "{\"choices\":[{\"message\":{\"content\":\"oi\"}}]}", new AtomicReference<>(),
                new AtomicReference<>());
        var strict = new ChatModelClient(new BotProperties(true, false, 5000, 0), JsonMapper.builder().build());
        assertThatThrownBy(() -> strict.complete(base, null, "m", List.of(new Turn("user", "oi"))))
                .isInstanceOf(ModelException.class).hasMessageContaining("recusado");
    }
}

package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.event.GatewayEvent.MessageReceived;
import dev.msgforge.support.IntegrationTest;
import dev.msgforge.web.ManagerStreams;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Tempo real do Manager (ADR 0013): o stream SSE abre com a sessão do Manager e recebe o trecho de
 * HTML depois de um evento do gateway stub.
 */
class ManagerStreamIT extends IntegrationTest {

    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Duration WAIT = Duration.ofSeconds(10);

    @LocalServerPort
    int port;

    @Autowired
    ApplicationEventPublisher events;

    private final List<Stream> opened = new ArrayList<>();

    @AfterEach
    void closeStreams() {
        opened.forEach(Stream::close);
    }

    // ------------------------------------------------------------ apoio

    /** Um evento SSE recebido: nome e dados (linhas "data:" juntadas com \n, como no navegador). */
    record Event(String name, String data) {}

    /** Stream aberto: as linhas são lidas numa thread virtual e viram eventos na fila. */
    final class Stream implements AutoCloseable {
        final HttpResponse<InputStream> response;
        final LinkedBlockingQueue<Event> received = new LinkedBlockingQueue<>();

        Stream(HttpResponse<InputStream> response) {
            this.response = response;
            Thread.ofVirtual().start(this::read);
        }

        private void read() {
            try (var lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String name = "message";
                var data = new ArrayList<String>();
                for (String line; (line = lines.readLine()) != null; ) {
                    if (line.isEmpty()) {
                        if (!data.isEmpty()) {
                            received.add(new Event(name, String.join("\n", data)));
                        }
                        name = "message";
                        data.clear();
                    } else if (line.startsWith("event:")) {
                        name = line.substring(6).strip();
                    } else if (line.startsWith("data:")) {
                        data.add(line.substring(line.startsWith("data: ") ? 6 : 5));
                    }
                }
            } catch (Exception closed) {
                // fim do teste
            }
        }

        Event await(String name, Predicate<String> data) throws InterruptedException {
            var deadline = System.nanoTime() + WAIT.toNanos();
            var seen = new ArrayList<String>();
            while (System.nanoTime() < deadline) {
                var event = received.poll(100, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                seen.add(event.name());
                if (event.name().equals(name) && data.test(event.data())) {
                    return event;
                }
            }
            throw new AssertionError("evento " + name + " não chegou; recebidos: " + seen);
        }

        @Override
        public void close() {
            try {
                response.body().close();
            } catch (Exception ignored) {
                // já fechado
            }
        }
    }

    /** Cliente HTTP com a sessão do Manager (login pelo formulário, com CSRF). */
    private HttpClient loggedIn() throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var page = client.send(HttpRequest.newBuilder(url("/manager/login")).build(),
                HttpResponse.BodyHandlers.ofString());
        var csrf = CSRF.matcher(page.body());
        assertThat(csrf.find()).as("token CSRF no formulário de login").isTrue();
        var form = "username=manager&apikey=" + URLEncoder.encode(API_KEY, StandardCharsets.UTF_8)
                + "&_csrf=" + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
        var login = client.send(HttpRequest.newBuilder(url("/manager/login"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(login.statusCode()).isEqualTo(302);
        assertThat(login.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).endsWith("/manager"));
        return client;
    }

    private HttpResponse<InputStream> request(HttpClient client, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(url(path)).header("Accept", "text/event-stream").build(),
                HttpResponse.BodyHandlers.ofInputStream());
    }

    private Stream open(HttpClient client, String path) throws Exception {
        var response = request(client, path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(response.headers().firstValue("X-Accel-Buffering")).hasValue("no");
        var stream = new Stream(response);
        opened.add(stream);
        return stream;
    }

    private URI url(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private String createInstance() {
        var name = uniqueName("tempo-real");
        assertThat(post("/instance/create", """
                {"instanceName":"%s"}""".formatted(name)).status()).isEqualTo(201);
        return name;
    }

    // ------------------------------------------------------------ testes

    @Test
    void instancePanelReceivesTheQrAsSoonAsTheGatewayPublishesIt() throws Exception {
        var name = createInstance();
        var stream = open(loggedIn(), "/manager/stream/instances/" + name);

        // Trecho atual logo ao abrir: cobre o que mudou entre a página e o stream
        var initial = stream.await("live", html -> html.contains("id=\"live\""));
        assertThat(initial.data()).doesNotContain("stub-qr-").doesNotContain("<html");

        assertThat(get("/instance/connect/" + name).status()).isEqualTo(200);

        var live = stream.await("live", html -> html.contains("stub-qr-"));
        assertThat(live.data())
                .contains("sse-swap=\"live\"")                 // o trecho trocado continua ligado ao stream
                .contains("hx-trigger=\"every 3s\"")           // e mantém o polling de reserva
                .contains("badge connecting")
                .contains("/manager/instances/" + name + "/live");   // links completos fora da requisição
    }

    @Autowired
    ManagerStreams streams;

    /** O Spring pausa e retoma contextos (stop/start); os streams precisam abrir de novo depois. */
    @Test
    void streamsOpenAgainAfterTheLifecycleRestarts() throws Exception {
        var name = createInstance();
        streams.stop();
        streams.start();

        var stream = open(loggedIn(), "/manager/stream/instances");
        stream.await("table", html -> html.contains(name));
    }

    @Test
    void instanceListReceivesTheNewStateOfAnyInstance() throws Exception {
        var name = createInstance();
        var stream = open(loggedIn(), "/manager/stream/instances");
        stream.await("table", html -> html.contains(name));

        get("/instance/connect/" + name);

        var table = stream.await("table", html -> html.contains(name) && html.contains("badge connecting"));
        assertThat(table.data()).contains("id=\"instances-table\"").contains("sse-swap=\"table\"");
    }

    @Test
    void messagesStreamPushesTheChatListAndTheOpenConversation() throws Exception {
        var name = createInstance();
        var stream = open(loggedIn(), "/manager/stream/instances/" + name + "/messages");
        stream.await("chats", html -> html.contains("id=\"chats\""));

        var jid = "5561988887777@s.whatsapp.net";
        events.publishEvent(new MessageReceived(name, Map.of(
                "key", Map.of("remoteJid", jid, "fromMe", false, "id", "SSE-1"),
                "pushName", "Ana",
                "status", "DELIVERY_ACK",
                "message", Map.of("conversation", "primeira linha\nsegunda <b>linha</b>"),
                "messageType", "conversation",
                "messageTimestamp", 1_700_000_000L)));

        var chats = stream.await("chats", html -> html.contains("Ana"));
        assertThat(chats.data()).contains("primeira linha");
        var conversation = stream.await("conversation:" + jid, html -> html.contains("segunda"));
        assertThat(conversation.data())
                .contains("primeira linha\nsegunda &lt;b&gt;linha&lt;/b&gt;")   // quebra de linha preservada, HTML escapado
                .contains("sse-swap=\"conversation:" + jid + "\"");
    }

    @Test
    void streamsRequireTheManagerSession() throws Exception {
        var anonymous = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        var response = request(anonymous, "/manager/stream/instances");
        assertThat(response.statusCode()).isEqualTo(401);
        response.body().close();

        // A chave da API num header não abre o Manager
        var withKey = anonymous.send(HttpRequest.newBuilder(url("/manager/stream/instances"))
                        .header("Accept", "text/event-stream").header("apikey", API_KEY).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(withKey.statusCode()).isEqualTo(401);
    }

    @Test
    void unknownInstanceIsNotFound() throws Exception {
        var response = request(loggedIn(), "/manager/stream/instances/nao-existe-" + port);
        assertThat(response.statusCode()).isEqualTo(404);
        response.body().close();
    }

    @Test
    void eachSessionHasALimitOfOpenStreams() throws Exception {
        var client = loggedIn();
        for (int i = 0; i < 4; i++) {
            open(client, "/manager/stream/instances").await("table", html -> true);
        }
        var refused = request(client, "/manager/stream/instances");
        assertThat(refused.statusCode()).isEqualTo(429);
        refused.body().close();

        // Outra sessão não é afetada
        open(loggedIn(), "/manager/stream/instances").await("table", html -> true);
    }
}

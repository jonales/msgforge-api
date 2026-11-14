package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.access.AccessAdminService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Organization;
import dev.msgforge.event.GatewayEvent.MessageReceived;
import dev.msgforge.support.AccessFixtures;
import org.springframework.jdbc.core.JdbcTemplate;
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

    @Autowired
    AccessAdminService admin;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void closeStreams() {
        opened.forEach(Stream::close);
        AccessFixtures.reset(jdbc);
    }

    // ------------------------------------------------------------ apoio

    /** Um evento SSE recebido: nome e dados (linhas "data:" juntadas com \n, como no navegador). */
    record Event(String name, String data) {}

    /** Stream aberto: as linhas são lidas numa thread virtual e viram eventos na fila. */
    final class Stream implements AutoCloseable {
        final HttpResponse<InputStream> response;
        final LinkedBlockingQueue<Event> received = new LinkedBlockingQueue<>();
        /** Tudo o que chegou, também o que o {@link #await} já consumiu. */
        final java.util.Queue<Event> history = new java.util.concurrent.ConcurrentLinkedQueue<>();

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
                            var event = new Event(name, String.join("\n", data));
                            history.add(event);
                            received.add(event);
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

    /** Cliente HTTP com a sessão do Manager (login pelo formulário, com CSRF), pela chave global. */
    private HttpClient loggedIn() throws Exception {
        return loggedIn("username=manager&apikey=" + URLEncoder.encode(API_KEY, StandardCharsets.UTF_8));
    }

    /** Com e-mail e senha de um usuário do Manager. */
    private HttpClient loggedInAs(String email, String password) throws Exception {
        return loggedIn("username=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8));
    }

    private HttpClient loggedIn(String credentials) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var page = client.send(HttpRequest.newBuilder(url("/manager/login")).build(),
                HttpResponse.BodyHandlers.ofString());
        var csrf = CSRF.matcher(page.body());
        assertThat(csrf.find()).as("token CSRF no formulário de login").isTrue();
        var form = credentials + "&_csrf=" + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8);
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

        // Nunca conectada: o selo diz "Aguardando conexão" e o estado de fato vai em data-state
        var table = stream.await("table", html -> html.contains(name) && html.contains("data-state=\"connecting\""));
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

    /**
     * ADR 0010, fatia 5: a lista vai renderizada por conteúdo, e não uma vez para todos. O Cliente,
     * com escopo numa instância só, nunca recebe a linha de outra, nem quando ela muda; a chave
     * global, no mesmo canal, recebe as duas.
     */
    @Test
    void theClientNeverReceivesARowOutsideItsScope() throws Exception {
        var mine = createInstance();
        var other = createInstance();
        var user = admin.createUser(ManagerPrincipal.GLOBAL_KEY, Organization.DEFAULT_ID, "cliente-sse@exemplo.com",
                "senha-inicial-123", AccessFixtures.CLIENTE, false).getId();
        jdbc.update("UPDATE membership SET all_instances = false WHERE user_id = ?", user);
        jdbc.update("INSERT INTO membership_instance (membership_id, instance_id) "
                + "SELECT m.id, i.id FROM membership m, instance i WHERE m.user_id = ? AND i.name = ?", user, mine);

        var client = loggedInAs("cliente-sse@exemplo.com", "senha-inicial-123");
        var clientList = open(client, "/manager/stream/instances");
        var keyList = open(loggedIn(), "/manager/stream/instances");
        assertThat(clientList.await("table", html -> html.contains(mine)).data()).doesNotContain(other);
        keyList.await("table", html -> html.contains(mine) && html.contains(other));

        // A instância fora do escopo muda de estado: a chave global vê, o Cliente recebe a lista sem ela
        get("/instance/connect/" + other);
        keyList.await("table", html -> html.contains(other) && html.contains("data-state=\"connecting\""));
        get("/instance/connect/" + mine);
        var table = clientList.await("table", html -> html.contains("data-state=\"connecting\""));
        assertThat(table.data()).contains(mine).doesNotContain(other);
        assertThat(clientList.history).noneMatch(event -> event.data().contains(other));

        // Os streams de instância e de conversas conferem a permissão na abertura
        var hidden = request(client, "/manager/stream/instances/" + other);
        assertThat(hidden.statusCode()).isEqualTo(404);
        hidden.body().close();
        var chats = request(client, "/manager/stream/instances/" + mine + "/messages");
        assertThat(chats.statusCode()).isEqualTo(403);
        chats.body().close();
        open(client, "/manager/stream/instances/" + mine).await("live", html -> html.contains("stub-qr-"));
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

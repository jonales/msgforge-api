package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.event.GatewayEvent.MessageReceived;
import dev.msgforge.support.IntegrationTest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Bot com IA (ADR 0015) de ponta a ponta, com um modelo falso em 127.0.0.1 e o gateway stub. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"msgforge.bot.allow-private-networks=true", "msgforge.bot.echo-window-ms=200"})
class BotIT extends IntegrationTest {

    private static final String ANA = "5561911112222@s.whatsapp.net";
    private static final String BIA = "5561933334444@s.whatsapp.net";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApplicationEventPublisher events;

    private HttpServer model;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private String name;
    private int incoming;

    @BeforeEach
    void start() throws Exception {
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/v1/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            var last = json.readTree(body).path("messages");
            var text = last.path(last.size() - 1).path("content").asString();
            var reply = json.writeValueAsString(Map.of("choices",
                    List.of(Map.of("message", Map.of("role", "assistant", "content", "Resposta: " + text)))));
            var bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        model.start();
        name = uniqueName("bot");
        assertThat(post("/instance/create", "{\"instanceName\":\"" + name + "\"}").status()).isEqualTo(201);
    }

    @AfterEach
    void stop() {
        model.stop(0);
    }

    private static MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder request) {
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                ManagerPrincipal.GLOBAL_KEY, null, List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))))).with(csrf());
    }

    private String base() {
        return "http://127.0.0.1:" + model.getAddress().getPort() + "/v1";
    }

    private void configure(String trigger, String keywords, String resumeMode) throws Exception {
        mvc.perform(asManager(MockMvcRequestBuilders.post("/manager/instances/" + name + "/integrations/bot"))
                        .param("enabled", "true").param("baseUrl", base()).param("model", "modelo-teste")
                        .param("apiKey", "sk-segredo-do-cliente").param("systemPrompt", "Você é o atendente da Loja.")
                        .param("triggerMode", trigger).param("keywords", keywords).param("stopKeyword", "sair")
                        .param("sessionMinutes", "0").param("ignoreGroups", "true").param("pauseOnHuman", "true")
                        .param("resumeMode", resumeMode).param("pauseMinutes", "30").param("contextMessages", "10")
                        .param("typingSeconds", "0"))
                .andExpect(flash().attribute("saved", "bot"));
    }

    private void receive(String from, String text) {
        incoming++;
        events.publishEvent(new MessageReceived(name, Map.of(
                "key", Map.of("remoteJid", from, "fromMe", false, "id", "IN-" + incoming),
                "pushName", "Cliente", "status", "DELIVERY_ACK",
                "message", Map.of("conversation", text), "messageType", "conversation",
                "messageTimestamp", System.currentTimeMillis() / 1000)));
    }

    private List<String> botReplies(String jid) {
        return jdbc.queryForList("""
                SELECT m.message ->> 'conversation' FROM message m JOIN instance i ON i.id = m.instance_id
                WHERE i.name = ? AND m.remote_jid = ? AND m.sent_by = 'BOT' ORDER BY m.created_at""",
                String.class, name, jid);
    }

    private static void eventually(Supplier<Boolean> condition) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("condição não atingida");
    }

    /** O bot trabalha em outra thread: depois de um tempo, nada de resposta nova. */
    private void staysQuiet(String jid, int replies) throws InterruptedException {
        Thread.sleep(700);
        assertThat(botReplies(jid)).hasSize(replies);
    }

    private String sessionStatus(String jid) {
        return jdbc.queryForObject("""
                SELECT s.status FROM bot_session s JOIN instance i ON i.id = s.instance_id
                WHERE i.name = ? AND s.remote_jid = ?""", String.class, name, jid);
    }

    @Test
    void answersWithTheModelPausesWhenAnAttendantRepliesAndCanBeReactivated() throws Exception {
        configure("ALL", "", "MANUAL");
        // A chave fica cifrada no banco
        var stored = jdbc.queryForObject("SELECT b.api_key_encrypted FROM instance_bot b JOIN instance i ON i.id = "
                + "b.instance_id WHERE i.name = ?", String.class, name);
        assertThat(stored).startsWith("v1:").doesNotContain("segredo");

        receive(ANA, "oi, tudo bem?");
        eventually(() -> botReplies(ANA).size() == 1);
        assertThat(botReplies(ANA)).containsExactly("Resposta: oi, tudo bem?");
        assertThat(authorizations).containsOnly("Bearer sk-segredo-do-cliente");
        assertThat(requests.getFirst()).contains("Você é o atendente da Loja.").contains("\"model\":\"modelo-teste\"");

        // Memória: a segunda chamada leva a conversa anterior
        receive(ANA, "qual o horário?");
        eventually(() -> botReplies(ANA).size() == 2);
        assertThat(requests.getLast()).contains("oi, tudo bem?").contains("Resposta: oi, tudo bem?")
                .contains("qual o horário?");

        // A conversa mostra o estado e os botões; o atendente responde pelo Manager e o bot pausa
        mvc.perform(asManager(MockMvcRequestBuilders.get("/manager/instances/" + name + "/messages")).param("jid", ANA))
                .andExpect(content().string(containsString("Bot respondendo")))
                .andExpect(content().string(containsString("Reativar o bot")))
                .andExpect(content().string(containsString("pelo bot")));
        mvc.perform(asManager(MockMvcRequestBuilders.post("/manager/instances/" + name + "/messages/reply"))
                        .param("jid", ANA).param("text", "Oi, aqui é a Ana da loja"))
                .andExpect(status().is3xxRedirection());
        assertThat(sessionStatus(ANA)).isEqualTo("PAUSED");
        receive(ANA, "obrigado");
        staysQuiet(ANA, 2);

        // Reativar pelo htmx devolve a conversa com o estado novo; o bot volta a responder
        mvc.perform(asManager(MockMvcRequestBuilders.post("/manager/instances/" + name + "/messages/bot"))
                        .header("HX-Request", "true").param("jid", ANA).param("action", "resume"))
                .andExpect(content().string(containsString("Bot respondendo")));
        receive(ANA, "mais uma dúvida");
        eventually(() -> botReplies(ANA).size() == 3);

        // Palavra de encerrar: sai da conversa e, no modo "todas", só volta reativado
        receive(ANA, "Sair");
        eventually(() -> "CLOSED".equals(sessionStatus(ANA)));
        receive(ANA, "voltei");
        staysQuiet(ANA, 3);

        // Mensagem enviada pelo celular (não pela Msgforge) também é um atendente assumindo
        receive(BIA, "olá");
        eventually(() -> botReplies(BIA).size() == 1);
        Thread.sleep(300);   // fora da janela do eco do bot (200 ms nos testes)
        events.publishEvent(new MessageReceived(name, Map.of(
                "key", Map.of("remoteJid", BIA, "fromMe", true, "id", "DO-CELULAR"),
                "message", Map.of("conversation", "deixa comigo"), "messageType", "conversation",
                "messageTimestamp", System.currentTimeMillis() / 1000)));
        eventually(() -> "PAUSED".equals(sessionStatus(BIA)));
    }

    @Test
    void keywordModeOnlyStartsOnAKeywordAndTheFormValidates() throws Exception {
        mvc.perform(asManager(MockMvcRequestBuilders.post("/manager/instances/" + name + "/integrations/bot"))
                        .param("enabled", "true").param("baseUrl", base()).param("model", "m")
                        .param("triggerMode", "KEYWORD").param("keywords", " , "))
                .andExpect(flash().attribute("fieldErrors", Map.of("bot.keywords", "manager.bot.error.keywords.required")));
        mvc.perform(asManager(MockMvcRequestBuilders.post("/manager/instances/" + name + "/integrations/bot"))
                        .param("baseUrl", "ftp://x").param("model", "m"))
                .andExpect(flash().attribute("fieldErrors", Map.of("bot.baseUrl", "manager.bot.error.baseUrl.invalid")));

        configure("KEYWORD", "orçamento, preço", "AUTOMATIC");
        receive(ANA, "bom dia");
        staysQuiet(ANA, 0);
        receive(ANA, "queria um ORCAMENTO");
        eventually(() -> botReplies(ANA).size() == 1);
        receive(ANA, "pra amanhã");   // já na conversa: responde sem palavra-chave
        eventually(() -> botReplies(ANA).size() == 2);

        // Grupos ficam de fora
        receive("120363000000000000@g.us", "orçamento");
        staysQuiet("120363000000000000@g.us", 0);

        // A tela: integrações mostra ligado, e o teste pergunta ao modelo sem passar pelo WhatsApp
        mvc.perform(asManager(MockMvcRequestBuilders.get("/manager/instances/" + name + "/integrations")))
                .andExpect(content().string(containsString("/integrations/bot")))
                .andExpect(content().string(containsString("Ligado")));
        mvc.perform(asManager(MockMvcRequestBuilders.get("/manager/instances/" + name + "/integrations/bot")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Guardada. Em branco, continua a mesma")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("sk-segredo"))));
        mvc.perform(asManager(MockMvcRequestBuilders.post("/manager/instances/" + name + "/integrations/bot/test"))
                        .header("HX-Request", "true").param("text", "teste da tela"))
                .andExpect(content().string(containsString("Resposta: teste da tela")));
        assertThat(botReplies(ANA)).hasSize(2);
    }
}

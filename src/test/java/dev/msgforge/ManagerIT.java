package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.support.IntegrationTest;
import dev.msgforge.webhook.WebhookService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Manager (ADR 0005): segurança, telas e fluxos, sobre o gateway stub. */
@AutoConfigureMockMvc
class ManagerIT extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    InstanceRepository instances;

    @Autowired
    WebhookService webhookService;

    private static MockHttpServletRequestBuilder GET(String url) {
        return MockMvcRequestBuilders.get(url);
    }

    private static MockHttpServletRequestBuilder POST(String url) {
        return MockMvcRequestBuilders.post(url);
    }

    private static MockHttpServletRequestBuilder asManager(MockHttpServletRequestBuilder request) {
        return request.with(user("manager").roles("MANAGER"));
    }

    private String createInstance() throws Exception {
        var name = uniqueName("tela");
        mvc.perform(asManager(POST("/manager/instances")).with(csrf()).param("instanceName", name))
                .andExpect(redirectedUrl("/manager/instances/" + name));
        return name;
    }

    @Test
    void everythingButLoginAndAssetsRequiresTheManagerSession() throws Exception {
        mvc.perform(GET("/manager")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/login"));
        mvc.perform(GET("/manager/instances/qualquer")).andExpect(status().is3xxRedirection());
        mvc.perform(GET("/manager/login")).andExpect(status().isOk()).andExpect(view().name("manager/login"));
        mvc.perform(GET("/manager/manager.css")).andExpect(status().isOk());
        mvc.perform(GET("/manager/vendor/htmx-2.0.11.min.js")).andExpect(status().isOk());
        mvc.perform(GET("/manager/vendor/htmx-ext-sse-2.2.4.min.js")).andExpect(status().isOk());
        mvc.perform(GET("/manager/manager.js")).andExpect(status().isOk());
        // Sem ícone declarado, o navegador pede /favicon.ico, que cai na API (401) e suja o console
        mvc.perform(GET("/manager/favicon.svg")).andExpect(status().isOk());
        mvc.perform(GET("/manager/login")).andExpect(content().string(containsString("/manager/favicon.svg")));
        mvc.perform(GET("/manager/stream/instances")).andExpect(status().is3xxRedirection());
        // Um token de instância não abre o Manager
        mvc.perform(GET("/manager").header("apikey", API_KEY)).andExpect(status().is3xxRedirection());
    }

    @Test
    void loginUsesTheGlobalApiKey() throws Exception {
        mvc.perform(POST("/manager/login").with(csrf()).param("username", "manager").param("apikey", "errada"))
                .andExpect(redirectedUrl("/manager/login?error"));
        mvc.perform(POST("/manager/login").with(csrf()).param("username", "manager").param("apikey", API_KEY))
                .andExpect(redirectedUrl("/manager"));
        mvc.perform(POST("/manager/login").param("username", "manager").param("apikey", API_KEY))
                .andExpect(status().isForbidden());   // sem CSRF
    }

    @Test
    void tooManyWrongKeysBlockTheAddressEvenForTheRightKey() throws Exception {
        org.springframework.test.web.servlet.request.RequestPostProcessor fromAttacker = request -> {
            request.setRemoteAddr("203.0.113.77");
            return request;
        };
        for (int i = 0; i < 10; i++) {
            mvc.perform(POST("/manager/login").with(csrf()).with(fromAttacker)
                            .param("username", "manager").param("apikey", "chute-" + i))
                    .andExpect(redirectedUrl("/manager/login?error"));
        }
        mvc.perform(POST("/manager/login").with(csrf()).with(fromAttacker)
                        .param("username", "manager").param("apikey", API_KEY))
                .andExpect(redirectedUrl("/manager/login?blocked"));
        // Outro endereço continua entrando
        mvc.perform(POST("/manager/login").with(csrf()).param("username", "manager").param("apikey", API_KEY))
                .andExpect(redirectedUrl("/manager"));
    }

    @Test
    void expiredSessionDuringHtmxPollingRedirectsTheWholePage() throws Exception {
        mvc.perform(GET("/manager/instances/table").header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/manager/login"));
    }

    @Test
    void formsRequireCsrf() throws Exception {
        mvc.perform(asManager(POST("/manager/instances")).param("instanceName", uniqueName("x")))
                .andExpect(status().isForbidden());
    }

    @Test
    void createShowsTheTokenOnceAndNeverAgain() throws Exception {
        var name = uniqueName("tela");
        var created = mvc.perform(asManager(POST("/manager/instances")).with(csrf())
                        .param("instanceName", name).param("number", "5561999999999"))
                .andExpect(redirectedUrl("/manager/instances/" + name))
                .andExpect(flash().attributeExists("createdToken"))
                .andReturn();
        var token = (String) created.getFlashMap().get("createdToken");
        assertThat(instances.findByName(name)).get().extracting(i -> i.getToken()).isEqualTo(token);

        mvc.perform(asManager(GET("/manager/instances/" + name)))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/instance"))
                .andExpect(model().attributeExists("instance", "live", "webhook", "eventNames"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(token))))
                .andExpect(content().string(Matchers.containsString("sse-connect=\"/manager/stream/instances/" + name + "\"")))
                .andExpect(content().string(Matchers.containsString("sse-swap=\"live\"")));
        mvc.perform(asManager(GET("/manager")))
                .andExpect(view().name("manager/instances"))
                .andExpect(content().string(Matchers.containsString(name)))
                .andExpect(content().string(Matchers.containsString("sse-connect=\"/manager/stream/instances\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString(token))));
    }

    @Test
    void connectShowsTheQrInTheLiveFragment() throws Exception {
        var name = createInstance();
        mvc.perform(asManager(POST("/manager/instances/" + name + "/connect")).with(csrf()))
                .andExpect(redirectedUrl("/manager/instances/" + name));

        mvc.perform(asManager(GET("/manager/instances/" + name + "/live")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("stub-qr-")))
                .andExpect(content().string(Matchers.containsString("hx-trigger=\"every 3s\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("<html"))));
    }

    @Test
    void historySyncDefaultsToNoneAndConnectSavesTheChoice() throws Exception {
        var name = createInstance();
        assertThat(instances.findByName(name)).get().extracting(i -> i.getHistorySync())
                .isEqualTo(dev.msgforge.instance.HistorySync.NONE);
        mvc.perform(asManager(GET("/manager/instances/" + name)))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"historySync\"")))
                .andExpect(content().string(Matchers.containsString("aria-describedby=\"history-hint\"")));

        mvc.perform(asManager(POST("/manager/instances/" + name + "/connect")).with(csrf())
                        .param("historySync", "FULL"))
                .andExpect(redirectedUrl("/manager/instances/" + name));
        assertThat(instances.findByName(name)).get().extracting(i -> i.getHistorySync())
                .isEqualTo(dev.msgforge.instance.HistorySync.FULL);

        // Criar já com a escolha; valor desconhecido vira o padrão
        var other = uniqueName("tela");
        mvc.perform(asManager(POST("/manager/instances")).with(csrf())
                        .param("instanceName", other).param("historySync", "RECENT"))
                .andExpect(redirectedUrl("/manager/instances/" + other));
        assertThat(instances.findByName(other)).get().extracting(i -> i.getHistorySync())
                .isEqualTo(dev.msgforge.instance.HistorySync.RECENT);
        assertThat(dev.msgforge.instance.HistorySync.parse("talvez")).isEqualTo(dev.msgforge.instance.HistorySync.NONE);
    }

    @Test
    void webhookFormSavesThroughTheService() throws Exception {
        var name = createInstance();
        mvc.perform(asManager(POST("/manager/instances/" + name + "/webhook")).with(csrf())
                        .param("url", "https://example.com/hook").param("enabled", "true")
                        .param("events", "MESSAGES_UPSERT", "SEND_MESSAGE"))
                .andExpect(flash().attribute("success", "manager.flash.webhookSaved"));

        var saved = webhookService.find(name).orElseThrow();
        assertThat(saved.url()).isEqualTo("https://example.com/hook");
        assertThat(saved.events()).containsExactly("MESSAGES_UPSERT", "SEND_MESSAGE");
        assertThat(saved.webhookByEvents()).isFalse();
    }

    @Test
    void serviceErrorsBecomeFlashMessagesNotJson() throws Exception {
        var name = createInstance();
        mvc.perform(asManager(POST("/manager/instances/" + name + "/webhook")).with(csrf()).param("url", "ftp://x"))
                .andExpect(redirectedUrl("/manager/instances/" + name))
                .andExpect(flash().attribute("errorText", Matchers.containsString("Invalid webhook url")));
        mvc.perform(asManager(POST("/manager/instances")).with(csrf()).param("instanceName", name))
                .andExpect(redirectedUrl("/manager"))
                .andExpect(flash().attribute("errorText", Matchers.containsString("already in use")));
        mvc.perform(asManager(GET("/manager/instances/nao-existe")))
                .andExpect(redirectedUrl("/manager"))
                .andExpect(flash().attribute("errorText", Matchers.containsString("does not exist")));
        mvc.perform(asManager(POST("/manager/instances/" + name + "/send")).with(csrf())
                        .param("number", "abc").param("text", "oi"))
                .andExpect(flash().attribute("errorText", Matchers.containsString("Invalid number")));
    }

    @Test
    void sendLogoutAndDelete() throws Exception {
        var name = createInstance();
        mvc.perform(asManager(POST("/manager/instances/" + name + "/send")).with(csrf())
                        .param("number", "5561999999999").param("text", "oi do manager"))
                .andExpect(flash().attribute("success", "manager.flash.sent"))
                .andExpect(flash().attributeExists("sentId"));
        mvc.perform(asManager(POST("/manager/instances/" + name + "/logout")).with(csrf()))
                .andExpect(flash().attribute("success", "manager.flash.loggedOut"));
        mvc.perform(asManager(POST("/manager/instances/" + name + "/delete")).with(csrf()))
                .andExpect(redirectedUrl("/manager"));
        assertThat(instances.findByName(name)).isEmpty();
    }

    @Test
    void theApiIsUntouchedByTheManagerSecurity() throws Exception {
        mvc.perform(GET("/instance/fetchInstances").header("apikey", API_KEY))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(header().doesNotExist("X-Frame-Options"));
        mvc.perform(GET("/instance/fetchInstances")).andExpect(status().isUnauthorized());
        // POST da API não exige CSRF
        mvc.perform(POST("/instance/create").header("apikey", API_KEY)
                        .contentType("application/json").content("{\"instanceName\":\"" + uniqueName("api") + "\"}"))
                .andExpect(status().isCreated());
    }

    @Autowired
    org.springframework.context.ApplicationEventPublisher events;

    @Test
    void messagesScreenShowsChatsAndTheConversation() throws Exception {
        var name = createInstance();
        // Envio pela tela de teste: também vai para o histórico
        mvc.perform(asManager(POST("/manager/instances/" + name + "/send")).with(csrf())
                        .param("number", "5561911111111").param("text", "olá do manager"))
                .andExpect(status().is3xxRedirection());
        events.publishEvent(new dev.msgforge.event.GatewayEvent.MessageReceived(name, java.util.Map.of(
                "key", java.util.Map.of("remoteJid", "5561911111111@s.whatsapp.net", "fromMe", false, "id", "IN-1"),
                "pushName", "Ana <b>",
                "message", java.util.Map.of("conversation", "oi, tudo bem?"),
                "messageType", "conversation",
                "messageTimestamp", java.time.Instant.now().getEpochSecond() + 60)));

        mvc.perform(GET("/manager/instances/" + name + "/messages")).andExpect(status().is3xxRedirection());

        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages")))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/messages"))
                .andExpect(model().attribute("chats", Matchers.hasSize(1)))
                .andExpect(content().string(Matchers.containsString("oi, tudo bem?")))
                .andExpect(content().string(Matchers.containsString("Ana &lt;b&gt;")))   // escapado
                // Tempo real (ADR 0013): o stream das conversas, com o polling de reserva
                .andExpect(content().string(Matchers.containsString(
                        "sse-connect=\"/manager/stream/instances/" + name + "/messages\"")))
                .andExpect(content().string(Matchers.containsString("sse-swap=\"chats\"")));

        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/chat"))
                        .param("jid", "5561911111111@s.whatsapp.net"))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("olá do manager")))
                .andExpect(content().string(Matchers.containsString("oi, tudo bem?")))
                .andExpect(content().string(Matchers.containsString(
                        "sse-swap=\"conversation:5561911111111@s.whatsapp.net\"")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("<html"))));   // só o fragmento

        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/chats")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("id=\"chats\"")));
    }

    @Test
    void settingsSectionRendersWithTheProxyCollapsed() throws Exception {
        var name = createInstance();
        mvc.perform(asManager(GET("/manager/instances/" + name)))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("settings", "proxy", "behaviors"))
                .andExpect(content().string(containsString("id=\"settings\"")))
                .andExpect(content().string(containsString("name=\"rejectCall\"")))
                .andExpect(content().string(containsString("Ainda sem efeito")))
                .andExpect(content().string(containsString("<details class=\"disclosure\" id=\"proxy\">")))
                .andExpect(content().string(containsString("type=\"password\"")));
    }

    @Test
    void settingsFormSavesAndNeedsCsrf() throws Exception {
        var name = createInstance();
        mvc.perform(asManager(POST("/manager/instances/" + name + "/settings"))
                        .param("rejectCall", "true").param("historySync", "FULL"))
                .andExpect(status().isForbidden());
        assertThat(instances.findByName(name)).get().extracting(i -> i.getSettings().rejectCall()).isEqualTo(false);

        mvc.perform(asManager(POST("/manager/instances/" + name + "/settings")).with(csrf())
                        .param("rejectCall", "true").param("msgCall", "Retorno depois")
                        .param("readStatus", "true").param("historySync", "RECENT"))
                .andExpect(redirectedUrl("/manager/instances/" + name + "#settings"))
                .andExpect(flash().attribute("settingsSaved", true));
        var saved = instances.findByName(name).orElseThrow();
        assertThat(saved.getSettings().rejectCall()).isTrue();
        assertThat(saved.getSettings().readStatus()).isTrue();
        assertThat(saved.getSettings().groupsIgnore()).isFalse();
        assertThat(saved.getSettings().msgCall()).isEqualTo("Retorno depois");
        assertThat(saved.getHistorySync()).isEqualTo(dev.msgforge.instance.HistorySync.RECENT);

        mvc.perform(asManager(GET("/manager/instances/" + name)).flashAttr("settingsSaved", true))
                .andExpect(content().string(containsString("Configurações salvas")))
                .andExpect(content().string(containsString("Retorno depois")));
    }

    @Test
    void proxyFormKeepsThePasswordHiddenAndShowsErrorsByTheField() throws Exception {
        var name = createInstance();
        mvc.perform(asManager(POST("/manager/instances/" + name + "/proxy")).with(csrf())
                        .param("enabled", "true").param("protocol", "socks5").param("host", "10.0.0.5")
                        .param("port", "1080").param("username", "cliente").param("password", "senha-secreta"))
                .andExpect(redirectedUrl("/manager/instances/" + name + "#proxy"))
                .andExpect(flash().attribute("proxySaved", true));
        var encrypted = instances.findByName(name).orElseThrow().getProxy().orElseThrow().passwordEncrypted();
        assertThat(encrypted).isNotNull();

        mvc.perform(asManager(GET("/manager/instances/" + name)))
                .andExpect(content().string(containsString("socks5://10.0.0.5:1080")))
                .andExpect(content().string(containsString("Há uma senha guardada")))
                .andExpect(content().string(Matchers.not(containsString("senha-secreta"))))
                .andExpect(content().string(Matchers.not(containsString(encrypted))));

        // Senha em branco, mesmo usuário: a guardada fica
        mvc.perform(asManager(POST("/manager/instances/" + name + "/proxy")).with(csrf())
                        .param("enabled", "true").param("protocol", "socks5").param("host", "10.0.0.6")
                        .param("port", "1080").param("username", "cliente").param("password", ""))
                .andExpect(flash().attribute("proxySaved", true));
        assertThat(instances.findByName(name).orElseThrow().getProxy().orElseThrow().passwordEncrypted())
                .isEqualTo(encrypted);

        var failed = mvc.perform(asManager(POST("/manager/instances/" + name + "/proxy")).with(csrf())
                        .param("enabled", "true").param("protocol", "http").param("host", "10.0.0.7")
                        .param("port", "99999").param("username", "cliente"))
                .andExpect(redirectedUrl("/manager/instances/" + name + "#proxy"))
                .andExpect(flash().attribute("fieldErrors",
                        java.util.Map.of("proxy.port", "manager.proxy.error.port.invalid")))
                .andReturn();
        assertThat(instances.findByName(name).orElseThrow().getProxy().orElseThrow().host()).isEqualTo("10.0.0.6");

        var flashed = failed.getFlashMap();
        mvc.perform(asManager(GET("/manager/instances/" + name))
                        .flashAttr("fieldErrors", flashed.get("fieldErrors")).flashAttr("proxy", flashed.get("proxy")))
                .andExpect(content().string(containsString("<details class=\"disclosure\" id=\"proxy\" open")))
                .andExpect(content().string(containsString("id=\"proxy-port-error\"")))
                .andExpect(content().string(containsString("A porta precisa ser um número de 1 a 65535.")))
                .andExpect(content().string(containsString("value=\"99999\"")))
                .andExpect(content().string(containsString("value=\"10.0.0.7\"")));

        mvc.perform(asManager(POST("/manager/instances/" + name + "/proxy")).with(csrf()))
                .andExpect(flash().attribute("proxySaved", true));
        assertThat(instances.findByName(name).orElseThrow().getProxy()).isEmpty();
    }

    // ------------------------------------------------------------ nova instância guiada

    private static MockHttpServletRequestBuilder inSession(MockHttpServletRequestBuilder request,
                                                           org.springframework.mock.web.MockHttpSession session) {
        return asManager(request).session(session);
    }

    @Test
    void guidedCreationWalksTheStepsAndSavesTheChosenHistory() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        var name = uniqueName("guiada");

        mvc.perform(inSession(GET("/manager/instances/new"), session))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/new-instance"))
                .andExpect(content().string(containsString("Passo 1 de 4")))
                .andExpect(content().string(containsString("aria-current=\"step\"")))
                .andExpect(content().string(containsString("<title>Passo 1 de 4: Nome")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(inSession(POST("/manager/instances/new"), session).with(csrf()).param("instanceName", " " + name + " "))
                .andExpect(redirectedUrl("/manager/instances/new/connection"));
        assertThat(instances.findByName(name)).isEmpty();   // só cria no passo do histórico

        mvc.perform(inSession(GET("/manager/instances/new/connection"), session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Passo 2 de 4")))
                .andExpect(content().string(containsString("id=\"method-qr\" name=\"method\" value=\"qr\" checked")));
        mvc.perform(inSession(POST("/manager/instances/new/connection"), session).with(csrf()).param("method", "qr"))
                .andExpect(redirectedUrl("/manager/instances/new/history"));

        mvc.perform(inSession(GET("/manager/instances/new/history"), session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Passo 3 de 4")))
                .andExpect(content().string(containsString("value=\"NONE\" checked")))
                .andExpect(content().string(containsString("não se desfaz depois de conectar")))
                .andExpect(content().string(containsString("Criar e gerar o QR code")));
        var created = mvc.perform(inSession(POST("/manager/instances/new/history"), session).with(csrf())
                        .param("historySync", "RECENT"))
                .andExpect(redirectedUrl("/manager/instances/" + name + "/connect"))
                .andExpect(flash().attributeExists("createdToken"))
                .andReturn();
        var saved = instances.findByName(name).orElseThrow();
        assertThat(saved.getHistorySync()).isEqualTo(dev.msgforge.instance.HistorySync.RECENT);
        assertThat(saved.getNumber()).isNull();

        var token = (String) created.getFlashMap().get("createdToken");
        mvc.perform(inSession(GET("/manager/instances/" + name + "/connect"), session))   // consome o flash da sessão
                .andExpect(status().isOk())
                .andExpect(view().name("manager/connect"))
                .andExpect(content().string(containsString("Passo 4 de 4")))
                .andExpect(content().string(containsString("stub-qr-")))
                .andExpect(content().string(containsString("Como ler o QR code no celular")))
                .andExpect(content().string(containsString("Aparelhos conectados")))
                .andExpect(content().string(containsString("Quando expira, um novo aparece aqui sozinho")))
                .andExpect(content().string(containsString("sse-connect=\"/manager/stream/instances/" + name + "\"")))
                .andExpect(content().string(containsString("sse-swap=\"live\"")))
                .andExpect(content().string(containsString("Histórico neste pareamento: Recente.")))
                .andExpect(content().string(containsString(token)));
        // Recarregar: a mesma tela, sem o token (ele aparece uma vez só)
        mvc.perform(inSession(GET("/manager/instances/" + name + "/connect"), session))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.not(containsString(token))));
    }

    @Test
    void guidedCreationByPairingCodeNeedsTheNumber() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        var name = uniqueName("codigo");
        mvc.perform(inSession(POST("/manager/instances/new"), session).with(csrf()).param("instanceName", name))
                .andExpect(redirectedUrl("/manager/instances/new/connection"));

        mvc.perform(inSession(POST("/manager/instances/new/connection"), session).with(csrf())
                        .param("method", "pairing").param("number", ""))
                .andExpect(redirectedUrl("/manager/instances/new/connection"))
                .andExpect(flash().attribute("fieldError", "manager.wizard.number.error.required"));
        mvc.perform(inSession(GET("/manager/instances/new/connection"), session))
                .andExpect(content().string(containsString("Informe o número que vai receber o código.")))
                .andExpect(content().string(containsString("id=\"method-pairing\" name=\"method\" value=\"pairing\" checked")))
                .andExpect(content().string(containsString("aria-invalid=\"true\"")));
        mvc.perform(inSession(POST("/manager/instances/new/connection"), session).with(csrf())
                        .param("method", "pairing").param("number", "123"))
                .andExpect(flash().attribute("fieldError", "manager.wizard.number.error.invalid"));

        mvc.perform(inSession(POST("/manager/instances/new/connection"), session).with(csrf())
                        .param("method", "pairing").param("number", "+55 (61) 99999-9999"))
                .andExpect(redirectedUrl("/manager/instances/new/history"));
        mvc.perform(inSession(GET("/manager/instances/new/history"), session))
                .andExpect(content().string(containsString("Código de pareamento (5561999999999)")))
                .andExpect(content().string(containsString("Criar e gerar o código")));
        mvc.perform(inSession(POST("/manager/instances/new/history"), session).with(csrf()))
                .andExpect(redirectedUrl("/manager/instances/" + name + "/connect"));
        var saved = instances.findByName(name).orElseThrow();
        assertThat(saved.getNumber()).isEqualTo("5561999999999");
        assertThat(saved.getHistorySync()).isEqualTo(dev.msgforge.instance.HistorySync.NONE);   // padrão seguro

        mvc.perform(inSession(GET("/manager/instances/" + name + "/connect"), session))
                .andExpect(content().string(containsString("STUB-1234")))
                .andExpect(content().string(containsString("Conectar com número de telefone")))
                .andExpect(content().string(containsString("Gerar outro código")))
                .andExpect(content().string(containsString("name=\"number\" value=\"5561999999999\"")));
    }

    @Test
    void guidedCreationValidatesTheName() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        var taken = createInstance();
        // nome digitado → erro (chave) e o texto que a tela mostra junto do campo
        var cases = java.util.List.of(
                java.util.List.of("", "manager.wizard.name.error.required", "Dê um nome à instância."),
                java.util.List.of("com espaço", "manager.wizard.name.error.pattern", "Use só letras sem acento"),
                java.util.List.of("New", "manager.wizard.name.error.reserved", "Esse nome é usado pelo próprio painel"),
                java.util.List.of(taken, "manager.wizard.name.error.taken", "Já existe uma instância com esse nome."));
        for (var bad : cases) {
            mvc.perform(inSession(POST("/manager/instances/new"), session).with(csrf()).param("instanceName", bad.get(0)))
                    .andExpect(redirectedUrl("/manager/instances/new"))
                    .andExpect(flash().attribute("fieldError", bad.get(1)))
                    .andExpect(flash().attribute("typedName", bad.get(0)));
            mvc.perform(inSession(GET("/manager/instances/new"), session))
                    .andExpect(content().string(containsString(bad.get(2))))
                    .andExpect(content().string(containsString("aria-invalid=\"true\"")))
                    .andExpect(content().string(containsString("aria-describedby=\"instanceName-hint instanceName-error\"")))
                    .andExpect(content().string(containsString("value=\"" + bad.get(0) + "\"")));
        }
        // Sem nome válido, os passos seguintes mandam de volta ao primeiro
        mvc.perform(inSession(GET("/manager/instances/new/connection"), session))
                .andExpect(redirectedUrl("/manager/instances/new"));
        mvc.perform(inSession(GET("/manager/instances/new/history"), session))
                .andExpect(redirectedUrl("/manager/instances/new"));
    }

    @Test
    void guidedCreationRequiresCsrfAndTheManagerSession() throws Exception {
        mvc.perform(GET("/manager/instances/new")).andExpect(redirectedUrl("/manager/login"));
        mvc.perform(asManager(POST("/manager/instances/new")).param("instanceName", uniqueName("x")))
                .andExpect(status().isForbidden());
        mvc.perform(asManager(POST("/manager/instances/new/connection")).param("method", "qr"))
                .andExpect(status().isForbidden());
        mvc.perform(asManager(POST("/manager/instances/new/history")).param("historySync", "FULL"))
                .andExpect(status().isForbidden());
    }

    @Test
    void goingBackKeepsTheAnswersAndAfterCreatingPointsToTheConnection() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        var name = uniqueName("voltar");
        mvc.perform(inSession(POST("/manager/instances/new"), session).with(csrf()).param("instanceName", name));
        mvc.perform(inSession(POST("/manager/instances/new/connection"), session).with(csrf())
                .param("method", "pairing").param("number", "5561988887777"));

        // Voltar (link ou navegador): cada passo reabre com o que já foi respondido
        mvc.perform(inSession(GET("/manager/instances/new"), session))
                .andExpect(content().string(containsString("value=\"" + name + "\"")));
        mvc.perform(inSession(GET("/manager/instances/new/connection"), session))
                .andExpect(content().string(containsString("value=\"pairing\" checked")))
                .andExpect(content().string(containsString("value=\"5561988887777\"")))
                .andExpect(content().string(containsString("href=\"/manager/instances/new\"")));
        // Trocar o nome no passo 1 não perde a forma de conexão
        var renamed = uniqueName("voltar");
        mvc.perform(inSession(POST("/manager/instances/new"), session).with(csrf()).param("instanceName", renamed));
        mvc.perform(inSession(GET("/manager/instances/new/history"), session))
                .andExpect(content().string(containsString(renamed)))
                .andExpect(content().string(containsString("5561988887777")));

        mvc.perform(inSession(POST("/manager/instances/new/history"), session).with(csrf()))
                .andExpect(redirectedUrl("/manager/instances/" + renamed + "/connect"));
        // Voltar do navegador depois de criar: a página não deixa recriar e leva à conexão
        mvc.perform(inSession(GET("/manager/instances/new/history"), session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Instância já criada")))
                .andExpect(content().string(containsString("href=\"/manager/instances/" + renamed + "/connect\"")))
                .andExpect(content().string(Matchers.not(containsString("name=\"historySync\""))));
        mvc.perform(inSession(POST("/manager/instances/new/history"), session).with(csrf()))
                .andExpect(redirectedUrl("/manager/instances/new"));
        assertThat(instances.findByName(name)).isEmpty();
    }

    @Test
    void abandonedConnectionShowsAsPendingAndThePanelResumesIt() throws Exception {
        var session = new org.springframework.mock.web.MockHttpSession();
        var name = uniqueName("parou");
        mvc.perform(inSession(POST("/manager/instances/new"), session).with(csrf()).param("instanceName", name));
        mvc.perform(inSession(POST("/manager/instances/new/connection"), session).with(csrf()).param("method", "qr"));
        mvc.perform(inSession(POST("/manager/instances/new/history"), session).with(csrf()));

        assertThat(badgeInTheList(name)).isEqualTo("pending");
        mvc.perform(asManager(GET("/manager/instances/table")))
                .andExpect(content().string(containsString("class=\"badge pending\"")))
                .andExpect(content().string(containsString("Aguardando conexão")));
        mvc.perform(asManager(GET("/manager/instances/" + name)))
                .andExpect(content().string(containsString("Esta instância ainda não foi conectada")))
                .andExpect(content().string(containsString("href=\"/manager/instances/" + name + "/connect\"")));

        // Gerar outro QR pela tela guiada volta para ela
        mvc.perform(asManager(POST("/manager/instances/" + name + "/connect")).with(csrf()).param("next", "wizard"))
                .andExpect(redirectedUrl("/manager/instances/" + name + "/connect"));

        // Conectou: a tela mostra o sucesso e o caminho para o painel; a lista deixa de dizer "aguardando"
        events.publishEvent(new dev.msgforge.event.GatewayEvent.ConnectionUpdated(name,
                dev.msgforge.instance.ConnectionState.OPEN, null, "5561977776666@s.whatsapp.net", "Loja"));
        mvc.perform(asManager(GET("/manager/instances/" + name + "/live")))
                .andExpect(content().string(containsString("WhatsApp conectado.")))
                .andExpect(content().string(containsString("data-announce-key=\"open\"")))
                .andExpect(content().string(containsString("data-announce=\"WhatsApp conectado.\"")))
                .andExpect(content().string(containsString(
                        "class=\"button primary live-panel-link\" href=\"/manager/instances/" + name + "\"")));
        assertThat(badgeInTheList(name)).isEqualTo("open");
        mvc.perform(asManager(GET("/manager/instances/" + name)))
                .andExpect(content().string(Matchers.not(containsString("Esta instância ainda não foi conectada"))));
    }

    /** O selo da instância na lista (outras instâncias dos testes também estão lá). */
    private String badgeInTheList(String name) throws Exception {
        var model = mvc.perform(asManager(GET("/manager/instances/table"))).andReturn().getModelAndView().getModel();
        @SuppressWarnings("unchecked")
        var rows = (java.util.List<dev.msgforge.web.ManagerViews.InstanceRow>) model.get("instances");
        return rows.stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow().badge();
    }

    @Test
    void theListLinksToTheGuidedCreation() throws Exception {
        mvc.perform(asManager(GET("/manager")))
                .andExpect(content().string(containsString("href=\"/manager/instances/new\"")))
                .andExpect(content().string(Matchers.not(containsString("name=\"instanceName\""))));
    }

    // ------------------------------------------------------------ página inicial

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String html(String url) throws Exception {
        return mvc.perform(asManager(GET(url))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void connection(String name, dev.msgforge.instance.ConnectionState state, String wuid, String profile) {
        events.publishEvent(new dev.msgforge.event.GatewayEvent.ConnectionUpdated(name, state, null, wuid, profile));
    }

    @Test
    void emptyHomeInvitesToCreateTheFirstInstanceThroughTheGuidedFlow() throws Exception {
        var page = html("/manager");
        assertThat(page)
                .contains("Crie a primeira instância")
                .contains("Criar a primeira instância")
                .contains("href=\"/manager/instances/new\"")
                .contains("id=\"instances-table\"").contains("sse-swap=\"table\"").contains("data-poll")
                .doesNotContain("<table")
                .doesNotContain("data-list-filter");
    }

    @Test
    void eachStateHasItsOwnMainAction() throws Exception {
        var waiting = createInstance();          // nunca conectou
        var connected = createInstance();
        var dropped = createInstance();          // conectou e caiu
        var reconnecting = createInstance();     // conectou e está voltando
        connection(connected, dev.msgforge.instance.ConnectionState.OPEN, "5561911112222@s.whatsapp.net", "Loja Azul");
        connection(dropped, dev.msgforge.instance.ConnectionState.OPEN, "5561933334444@s.whatsapp.net", null);
        connection(dropped, dev.msgforge.instance.ConnectionState.CLOSE, null, null);
        connection(reconnecting, dev.msgforge.instance.ConnectionState.OPEN, "5561955556666@s.whatsapp.net", null);
        connection(reconnecting, dev.msgforge.instance.ConnectionState.CONNECTING, null, null);

        var model = mvc.perform(asManager(GET("/manager"))).andReturn().getModelAndView().getModel();
        @SuppressWarnings("unchecked")
        var rows = (java.util.List<dev.msgforge.web.ManagerViews.InstanceRow>) model.get("instances");
        var actions = rows.stream().collect(java.util.stream.Collectors.toMap(
                dev.msgforge.web.ManagerViews.InstanceRow::name, dev.msgforge.web.ManagerViews.InstanceRow::action));
        assertThat(actions).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                waiting, "resume", connected, "open", dropped, "reconnect", reconnecting, "follow"));

        var page = html("/manager");
        assertThat(page)
                // aguardando: volta para a conexão guiada, e o selo do PR #31 continua
                .contains("href=\"/manager/instances/" + waiting + "/connect\"")
                .contains("class=\"badge pending\" data-state=\"close\"").contains("Aguardando conexão")
                .contains("Continuar a conexão")
                // conectada: abre o painel, com número e nome do perfil
                .contains("<span>Abrir</span><span class=\"visually-hidden\"> " + connected + "</span>")
                .contains("5561911112222").contains("Loja Azul")
                // caiu depois de conectar: reconectar leva ao cartão "Conectar" do painel
                .contains("href=\"/manager/instances/" + dropped + "#connect\"").contains("Reconectar")
                .contains("class=\"badge close\" data-state=\"close\"")
                // voltando sozinha: acompanhar no painel
                .contains("Ver conexão").contains("class=\"badge connecting\" data-state=\"connecting\"")
                .contains("4 instâncias").contains("1 conectada").contains("2 precisam de ação")
                // poucas instâncias: sem busca
                .doesNotContain("data-list-filter");
        assertThat(html("/manager/instances/" + dropped)).contains("id=\"connect\"");
    }

    @Test
    void failingWebhookShowsInTheListFromTheOutboxQueue() throws Exception {
        var name = createInstance();
        assertThat(html("/manager/instances/table")).doesNotContain("Webhook falhando");
        // Entrega já tentada, à espera de nova tentativa (no futuro, para o trabalhador não pegá-la)
        jdbc.update("""
                INSERT INTO outbox (type, order_key, event, destination, payload, attempts, next_attempt_at)
                VALUES ('WEBHOOK', ?, 'messages.upsert', 'http://127.0.0.1:9/hook', '{}', 2, now() + interval '1 hour')
                """, name);
        try {
            assertThat(html("/manager/instances/table")).contains("Webhook falhando, tentando de novo");
        } finally {
            jdbc.update("DELETE FROM outbox WHERE order_key = ?", name);
        }
    }

    @Test
    void searchAndFilterOnlyAppearAboveTheLimit() throws Exception {
        for (int i = 0; i < 8; i++) {
            createInstance();
        }
        assertThat(html("/manager")).doesNotContain("data-list-filter").doesNotContain("id=\"instance-search\"");
        createInstance();
        assertThat(html("/manager"))
                .contains("data-list-filter").contains("id=\"instance-search\"").contains("id=\"instance-filter\"")
                .contains("data-count-text=\"{0} de {1} instâncias\"")
                .contains("data-search=").contains("9 instâncias");
        // O trecho ao vivo não carrega a busca (ela fica fora dele, para não perder o que foi digitado)
        assertThat(html("/manager/instances/table")).doesNotContain("data-list-filter");
    }

    @Test
    void comingSoonIntegrationsHaveNoLinkOrButton() throws Exception {
        var page = html("/manager");
        var start = page.indexOf("<section class=\"soon\"");
        assertThat(start).isPositive();
        var soon = page.substring(start, page.indexOf("</section>", start));
        assertThat(soon)
                .contains("Em breve").contains("Cloud API oficial").contains("Typebot").contains("Chatwoot")
                .contains("OpenAI").contains("Dify").contains("Flowise").contains("n8n")
                .doesNotContain("<a ").doesNotContain("<button").doesNotContain("href=");
    }
}

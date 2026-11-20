// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.support.IntegrationTest;
import dev.msgforge.webhook.WebhookService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
        // Como o login com a chave global deixa a sessão (admin da plataforma: vê tudo)
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                ManagerPrincipal.GLOBAL_KEY, null, List.of(new SimpleGrantedAuthority("ROLE_MANAGER")))));
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
                .andExpect(redirectedUrl("/manager/login?expired"));   // sem CSRF: não entra, volta ao login
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
                .andExpect(model().attributeExists("instance", "can", "engine", "deliveryFailing"))
                .andExpect(content().string(Matchers.not(Matchers.containsString(token))))
                // O resumo da visão geral é buscado de novo a cada evento "live" do stream
                .andExpect(content().string(Matchers.containsString("sse-connect=\"/manager/stream/instances/" + name + "\"")))
                .andExpect(content().string(Matchers.containsString("hx-trigger=\"sse:live, every 30s\"")));
        // A tela Conexão recebe o trecho vivo (estado + QR)
        mvc.perform(asManager(GET("/manager/instances/" + name + "/connection")))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/instance-connection"))
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
                .andExpect(redirectedUrl("/manager/instances/" + name + "/connection"));

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
        mvc.perform(asManager(GET("/manager/instances/" + name + "/connection")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("name=\"historySync\"")))
                .andExpect(content().string(Matchers.containsString("aria-describedby=\"history-hint\"")));

        mvc.perform(asManager(POST("/manager/instances/" + name + "/connect")).with(csrf())
                        .param("historySync", "FULL"))
                .andExpect(redirectedUrl("/manager/instances/" + name + "/connection"));
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
                .andExpect(redirectedUrl("/manager/instances/" + name + "/settings/webhook"))
                .andExpect(flash().attribute("saved", "webhook"));

        var saved = webhookService.find(name).orElseThrow();
        assertThat(saved.url()).isEqualTo("https://example.com/hook");
        assertThat(saved.events()).containsExactly("MESSAGES_UPSERT", "SEND_MESSAGE");
        assertThat(saved.webhookByEvents()).isFalse();
    }

    @Test
    void serviceErrorsBecomeFlashMessagesNotJson() throws Exception {
        var name = createInstance();
        mvc.perform(asManager(POST("/manager/instances/" + name + "/webhook")).with(csrf()).param("url", "ftp://x"))
                .andExpect(redirectedUrl("/manager/instances/" + name + "/settings/webhook"))
                .andExpect(flash().attribute("errorText", Matchers.containsString("Invalid webhook url")));
        mvc.perform(asManager(POST("/manager/instances")).with(csrf()).param("instanceName", name))
                .andExpect(redirectedUrl("/manager"))
                .andExpect(flash().attribute("errorText", Matchers.containsString("already in use")));
        mvc.perform(asManager(GET("/manager/instances/nao-existe")))
                .andExpect(status().isNotFound()).andExpect(view().name("manager/not-found"));
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
                .andExpect(model().attributeExists("chats", "can"))
                .andExpect(content().string(Matchers.containsString("oi, tudo bem?")))
                // Menu do painel, com "Mensagens" como página atual e o nome da instância no topo
                .andExpect(content().string(Matchers.containsString("class=\"panel-nav\"")))
                .andExpect(content().string(Matchers.containsString(
                        "href=\"/manager/instances/" + name + "/messages\" aria-current=\"page\"")))
                .andExpect(content().string(Matchers.containsString("class=\"panel-nav-title\">" + name + "</p>")))
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

    private void receive(String name, String jid, String participant, String pushName, String id, String type,
                         Object content, long timestamp) {
        var key = new java.util.HashMap<String, Object>(java.util.Map.of("remoteJid", jid, "fromMe", false, "id", id));
        if (participant != null) {
            key.put("participant", participant);
        }
        var data = new java.util.HashMap<String, Object>(java.util.Map.of("key", key,
                "message", java.util.Map.of(type, content), "messageType", type, "messageTimestamp", timestamp));
        if (pushName != null) {
            data.put("pushName", pushName);
        }
        events.publishEvent(new dev.msgforge.event.GatewayEvent.MessageReceived(name, data));
    }

    @Autowired
    tools.jackson.databind.json.JsonMapper json;

    @Test
    void conversationShowsEachMessageTypeAndGroupsApart() throws Exception {
        var name = createInstance();
        var group = "120363000000000999@g.us";
        var ana = "5561911111111@s.whatsapp.net";
        var lid = "222222222222222@lid";
        var now = java.time.Instant.now().getEpochSecond();
        receive(name, ana, null, "Ana", "D-1", "conversation", "oi direto", now - 100);
        // Foto com os campos de mídia (baixável); o áudio é de antes deles (sem como baixar)
        receive(name, group, ana, "Ana", "G-1", "imageMessage", java.util.Map.of("caption", "foto da obra",
                "mimetype", "image/jpeg", "directPath", "/v/t62/a.enc", "mediaKey", "AAAA"), now - 90);
        receive(name, group, ana, "Ana", "G-2", "audioMessage", java.util.Map.of("ptt", true, "seconds", 75), now - 80);
        receive(name, group, lid, null, "G-3", "documentMessage",
                java.util.Map.of("fileName", "contrato.pdf", "fileLength", 1_468_006), now - 70);
        receive(name, group, lid, null, "G-4", "locationMessage",
                java.util.Map.of("degreesLatitude", -15.78, "degreesLongitude", -47.92, "name", "Obra"), now - 60);
        receive(name, group, lid, null, "G-5", "pollCreationMessage", java.util.Map.of("name", "Qual dia?",
                "options", List.of(java.util.Map.of("optionName", "Seg"))), now - 50);
        receive(name, group, lid, null, "G-6", "reactionMessage", java.util.Map.of("text", "👍",
                "key", java.util.Map.of("remoteJid", group, "fromMe", false, "id", "G-5")), now - 40);
        receive(name, group, lid, null, "G-7", "contactMessage", java.util.Map.of("displayName", "Bia"), now - 35);
        receive(name, group, lid, null, "G-8", "stickerMessage", java.util.Map.of(), now - 30);
        receive(name, group, lid, null, "G-9", "buttonsResponseMessage", java.util.Map.of(), now - 20);
        receive(name, group, lid, null, "G-10", "conversation", "vai ser apagada", now - 10);
        events.publishEvent(new dev.msgforge.event.GatewayEvent.MessageDeleted(name, group, false, "G-10", lid));

        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/chats")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Pessoas")))
                .andExpect(content().string(containsString("Grupos")))
                // Sem nome conhecido, o grupo aparece pelo JID, e não pelo pushName de quem mandou
                .andExpect(content().string(containsString(group)))
                .andExpect(content().string(containsString("Mensagem apagada")));

        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/chat")).param("jid", group))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("foto da obra")))
                .andExpect(content().string(containsString("<img data-media")))
                .andExpect(content().string(containsString("/messages/media?jid=")))
                .andExpect(content().string(containsString("Mensagem de voz")))
                .andExpect(content().string(containsString("1:15")))
                // Áudio antigo, sem os campos de mídia e fora do motor: o aviso direto, sem player morto
                .andExpect(content().string(Matchers.not(containsString("<audio data-media"))))
                .andExpect(content().string(containsString("class=\"media audio media-failed\"")))
                .andExpect(content().string(containsString("Mensagem de voz indisponível agora")))
                .andExpect(content().string(containsString("contrato.pdf")))
                .andExpect(content().string(containsString("1,4 MB")))
                .andExpect(content().string(containsString("openstreetmap.org")))
                .andExpect(content().string(containsString("Qual dia?")))
                // A reação vai junto da enquete, sem balão próprio
                .andExpect(content().string(containsString("class=\"reaction-chip\"")))
                .andExpect(content().string(Matchers.not(containsString("Reagiu com 👍"))))
                .andExpect(content().string(Matchers.not(containsString("kind-reaction"))))
                .andExpect(content().string(containsString("Bia")))
                .andExpect(content().string(containsString("Figurinha")))
                .andExpect(content().string(containsString("(buttonsResponse)")))
                .andExpect(content().string(containsString("Mensagem apagada")))
                .andExpect(content().string(Matchers.not(containsString("vai ser apagada"))))
                // Remetente: nome e número; LID sem tradução fica como veio
                .andExpect(content().string(containsString("+5561911111111")))
                .andExpect(content().string(containsString(lid)))
                .andExpect(content().string(containsString("class=\"day-sep\"")))
                .andExpect(content().string(containsString("continued")));
    }

    @Test
    void reactionsShowOnTheTargetMessageAndInTheChatPreview() throws Exception {
        var name = createInstance();
        var ana = "5561955555555@s.whatsapp.net";
        var now = java.time.Instant.now().getEpochSecond();
        receive(name, ana, null, "Ana", "T-1", "conversation", "chego às nove", now - 60);
        // Reação pela API (fromMe), que vai para o histórico pela resposta do envio
        mvc.perform(POST("/message/sendReaction/" + name).header("apikey", API_KEY).contentType("application/json")
                        .content("{\"key\":{\"remoteJid\":\"" + ana + "\",\"fromMe\":false,\"id\":\"T-1\"},"
                                + "\"reaction\":\"👍\"}"))
                .andExpect(status().isCreated());
        // Reação da Ana (recebida) e a troca dela: vale a última
        receive(name, ana, null, "Ana", "R-1", "reactionMessage", java.util.Map.of("text", "🙂",
                "key", java.util.Map.of("remoteJid", ana, "fromMe", true, "id", "T-1")), now - 30);
        receive(name, ana, null, "Ana", "R-2", "reactionMessage", java.util.Map.of("text", "🙏",
                "key", java.util.Map.of("remoteJid", ana, "fromMe", true, "id", "T-1")), now + 5);

        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/chat")).param("jid", ana))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("chego às nove")))
                .andExpect(content().string(containsString("🙏")))
                .andExpect(content().string(containsString("👍")))
                .andExpect(content().string(Matchers.not(containsString("🙂"))))
                .andExpect(content().string(Matchers.not(containsString("kind-reaction"))));
        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/chats")))
                .andExpect(content().string(containsString("<span>Reagiu</span>")))
                .andExpect(content().string(containsString("<span class=\"reaction-emoji\">🙏</span>")))
                .andExpect(content().string(containsString("<span>a:</span>")))
                .andExpect(content().string(containsString("chego às nove")));
    }

    @Test
    void groupNameComesFromTheEngine() throws Exception {
        var name = createInstance();
        var created = mvc.perform(POST("/group/create/" + name).header("apikey", API_KEY).contentType("application/json")
                        .content("{\"subject\":\"Equipe de obra\",\"participants\":[\"5561922222222\"]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var groupJid = json.readTree(created).get("id").asString();
        receive(name, groupJid, "5561922222222@s.whatsapp.net", "Bia", "N-1", "conversation", "bom dia",
                java.time.Instant.now().getEpochSecond());
        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/chats")))
                .andExpect(content().string(containsString("Equipe de obra")))
                .andExpect(content().string(containsString("Bia:")));
        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/chat")).param("jid", groupJid))
                .andExpect(content().string(containsString("Equipe de obra")));
    }

    @Test
    void mediaComesFromTheGatewayWithASafeContentType() throws Exception {
        var name = createInstance();
        var png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        var sent = mvc.perform(POST("/message/sendMedia/" + name).header("apikey", API_KEY).contentType("application/json")
                        .content("{\"number\":\"5561933333333\",\"mediatype\":\"image\",\"mimetype\":\"image/png\","
                                + "\"caption\":\"pixel\",\"media\":\"" + png + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var id = json.readTree(sent).get("key").get("id").asString();
        var jid = "5561933333333@s.whatsapp.net";
        var url = "/manager/instances/" + name + "/messages/media";

        mvc.perform(GET(url).param("jid", jid).param("id", id).param("fromMe", "true"))
                .andExpect(status().is3xxRedirection());   // sem sessão
        mvc.perform(asManager(GET(url)).param("jid", jid).param("id", id).param("fromMe", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "sandbox; default-src 'none'"))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("inline")))
                .andExpect(result -> assertThat(result.getResponse().getContentAsByteArray())
                        .isEqualTo(java.util.Base64.getDecoder().decode(png)));
        // Range (o <audio>/<video> pede pedaços): 206 com Content-Range e o tamanho do pedaço
        var bytes = java.util.Base64.getDecoder().decode(png);
        mvc.perform(asManager(GET(url)).param("jid", jid).param("id", id).param("fromMe", "true")
                        .header("Range", "bytes=0-9"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string("Accept-Ranges", "bytes"))
                .andExpect(header().string("Content-Range", "bytes 0-9/" + bytes.length))
                .andExpect(header().string("Content-Length", "10"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(asManager(GET(url)).param("jid", jid).param("id", id).param("fromMe", "true"))
                .andExpect(header().string("Content-Length", String.valueOf(bytes.length)))
                .andExpect(header().string("Accept-Ranges", "bytes"));
        // A conversa mostra a imagem enviada pela própria instância
        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/chat")).param("jid", jid))
                .andExpect(content().string(containsString("id=" + id)))
                .andExpect(content().string(containsString("pixel")));
        // Fora do histórico ou instância que não existe: 404
        mvc.perform(asManager(GET(url)).param("jid", jid).param("id", "NAO-EXISTE").param("fromMe", "true"))
                .andExpect(status().isNotFound());
        mvc.perform(asManager(GET("/manager/instances/nao-existe-" + name + "/messages/media"))
                        .param("jid", jid).param("id", id).param("fromMe", "true"))
                .andExpect(status().isNotFound());
    }

    @Test
    void voiceNotesPlayAsOggOpusAndCanBeDownloaded() throws Exception {
        var name = createInstance();
        var ogg = java.util.Base64.getEncoder().encodeToString("OggS-nota-de-voz".getBytes());
        var sent = mvc.perform(POST("/message/sendWhatsAppAudio/" + name).header("apikey", API_KEY)
                        .contentType("application/json")
                        .content("{\"number\":\"5561944444444\",\"audio\":\"" + ogg + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(sent).get("message").get("audioMessage").get("ptt").asBoolean()).isTrue();
        var id = json.readTree(sent).get("key").get("id").asString();
        var jid = "5561944444444@s.whatsapp.net";
        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/media"))
                        .param("jid", jid).param("id", id).param("fromMe", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.matchesPattern("audio/ogg; ?codecs=opus")))
                .andExpect(header().string("Content-Disposition", Matchers.startsWith("inline")));
        mvc.perform(asManager(GET("/manager/instances/" + name + "/messages/chat")).param("jid", jid))
                .andExpect(content().string(containsString("<audio data-media")))
                .andExpect(content().string(containsString("Baixar o áudio")));
    }

    @Test
    void panelHasOneScreenPerMenuItem() throws Exception {
        var name = createInstance();
        var panel = "/manager/instances/" + name;
        // Visão geral: resumo e atalhos; nada de formulário de conectar, envio ou exclusão aqui
        mvc.perform(asManager(GET(panel)))
                .andExpect(status().isOk())
                .andExpect(model().attribute("engine", "stub"))
                .andExpect(content().string(containsString("Simulado (sem WhatsApp)")))
                .andExpect(content().string(Matchers.not(containsString("WHATSAPP-BAILEYS"))))
                .andExpect(content().string(containsString("href=\"" + panel + "\" aria-current=\"page\"")))
                .andExpect(content().string(containsString("href=\"" + panel + "/connection\"")))
                .andExpect(content().string(containsString("href=\"" + panel + "/send\"")))
                .andExpect(content().string(containsString("href=\"" + panel + "/settings\"")))
                .andExpect(content().string(Matchers.not(containsString("#connect"))))
                .andExpect(content().string(Matchers.not(containsString("action=\"" + panel + "/connect\""))))
                .andExpect(content().string(Matchers.not(containsString("action=\"" + panel + "/delete\""))))
                .andExpect(content().string(Matchers.not(containsString("action=\"" + panel + "/send\""))))
                .andExpect(content().string(containsString("class=\"panel-nav-title\"")))
                .andExpect(content().string(containsString("href=\"" + panel + "/integrations\"")));
        // O resumo sozinho (o que o SSE pede de novo)
        mvc.perform(asManager(GET(panel + "/summary")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"summary\"")))
                .andExpect(content().string(Matchers.not(containsString("<html"))));

        // Conexão: estado ao vivo, conectar (desconectada) e a zona de perigo
        mvc.perform(asManager(GET(panel + "/connection")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"" + panel + "/connection\" aria-current=\"page\"")))
                .andExpect(content().string(containsString("id=\"live\"")))
                .andExpect(content().string(containsString("action=\"" + panel + "/connect\"")))
                .andExpect(content().string(containsString("action=\"" + panel + "/logout\"")))
                .andExpect(content().string(containsString("action=\"" + panel + "/delete\"")))
                .andExpect(content().string(containsString("class=\"panel-nav-title\">" + name + "</p>")));

        // Envio de teste: tela própria, e o envio volta para ela
        mvc.perform(asManager(GET(panel + "/send")))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/instance-send"))
                .andExpect(content().string(containsString("href=\"" + panel + "/send\" aria-current=\"page\"")))
                .andExpect(content().string(containsString("action=\"" + panel + "/send\"")));
        mvc.perform(asManager(POST(panel + "/send")).with(csrf()).param("number", "5561999999999").param("text", " "))
                .andExpect(redirectedUrl(panel + "/send"));
        mvc.perform(asManager(POST(panel + "/send")).with(csrf()).param("number", "abc").param("text", "oi"))
                .andExpect(redirectedUrl(panel + "/send"));
        mvc.perform(asManager(POST(panel + "/logout")).with(csrf()))
                .andExpect(redirectedUrl(panel + "/connection"));

        // Configurações: abas com rótulos curtos (cabem numa linha)
        mvc.perform(asManager(GET(panel + "/settings/calls")))
                .andExpect(content().string(containsString(">Leitura<")))
                .andExpect(content().string(containsString(">Webhook<")));
    }

    @Test
    void replyFromTheOpenConversation() throws Exception {
        var name = createInstance();
        var jid = "5561933334444@s.whatsapp.net";
        var messages = "/manager/instances/" + name + "/messages";
        // A conversa pela URL (recarregar e voltar funcionam): a resposta já vem com o jid
        mvc.perform(asManager(GET(messages)).param("jid", jid))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-jid=\"" + jid + "\"")))
                .andExpect(content().string(containsString("id=\"reply\"")))
                .andExpect(content().string(containsString("value=\"" + jid + "\"")));
        // Pelo htmx: devolve a conversa com a mensagem enviada
        mvc.perform(asManager(POST(messages + "/reply")).with(csrf()).header("HX-Request", "true")
                        .param("jid", jid).param("text", "resposta do atendente"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("resposta do atendente")))
                .andExpect(content().string(Matchers.not(containsString("<html"))));
        // Sem texto: 422, sem trocar a conversa
        mvc.perform(asManager(POST(messages + "/reply")).with(csrf()).header("HX-Request", "true")
                        .param("jid", jid).param("text", "  "))
                .andExpect(status().is(422))
                .andExpect(content().string(containsString("Escreva a mensagem")));
        // Sem JS: volta para a conversa
        mvc.perform(asManager(POST(messages + "/reply")).with(csrf()).param("jid", jid).param("text", "oi"))
                .andExpect(redirectedUrl(messages + "?jid=" + jid));
    }

    @Test
    void settingsHaveOnePagePerGroupWithTheSavedState() throws Exception {
        var name = createInstance();
        var settings = "/manager/instances/" + name + "/settings";
        mvc.perform(asManager(GET(settings))).andExpect(redirectedUrl(settings + "/calls"));
        mvc.perform(GET(settings + "/calls")).andExpect(status().is3xxRedirection());   // sem sessão
        mvc.perform(asManager(GET(settings + "/nada"))).andExpect(status().isNotFound());

        mvc.perform(asManager(GET(settings + "/calls")))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/settings"))
                .andExpect(content().string(containsString("name=\"rejectCall\"")))
                .andExpect(content().string(containsString("name=\"msgCall\"")))
                .andExpect(content().string(Matchers.not(containsString("name=\"readMessages\""))))
                // Abas com o estado guardado, a atual marcada
                .andExpect(content().string(containsString("href=\"" + settings + "/calls\" aria-current=\"page\"")))
                .andExpect(content().string(containsString("Aceita ligações")))
                .andExpect(content().string(containsString("0 de 3 ligadas")))
                .andExpect(content().string(containsString("href=\"/manager/instances/" + name + "/settings\" aria-current=\"page\"")));
        mvc.perform(asManager(GET(settings + "/reading")))
                .andExpect(content().string(containsString("name=\"readMessages\"")))
                .andExpect(content().string(containsString("name=\"readStatus\"")))
                .andExpect(content().string(containsString("name=\"groupsIgnore\"")));
        // Presença: sem efeito, e a tela diz isso
        mvc.perform(asManager(GET(settings + "/presence")))
                .andExpect(content().string(containsString("name=\"alwaysOnline\"")))
                .andExpect(content().string(containsString("Ainda sem efeito")))
                .andExpect(content().string(containsString("Problema conhecido")));
        mvc.perform(asManager(GET(settings + "/history")))
                .andExpect(content().string(containsString("name=\"historySync\"")));
        mvc.perform(asManager(GET(settings + "/proxy")))
                .andExpect(content().string(containsString("type=\"password\"")));
        mvc.perform(asManager(GET(settings + "/webhook")))
                .andExpect(content().string(containsString("name=\"url\"")));
    }

    @Test
    void eachGroupSavesOnlyItsOwnOptionsAndNeedsCsrf() throws Exception {
        var name = createInstance();
        var settings = "/manager/instances/" + name + "/settings";
        mvc.perform(asManager(POST(settings + "/calls")).param("rejectCall", "true"))
                .andExpect(status().isForbidden());
        assertThat(instances.findByName(name)).get().extracting(i -> i.getSettings().rejectCall()).isEqualTo(false);

        mvc.perform(asManager(POST(settings + "/reading")).with(csrf())
                        .param("readStatus", "true").param("groupsIgnore", "true"))
                .andExpect(redirectedUrl(settings + "/reading"))
                .andExpect(flash().attribute("saved", "reading"));
        mvc.perform(asManager(POST(settings + "/calls")).with(csrf())
                        .param("rejectCall", "true").param("msgCall", "Retorno depois"))
                .andExpect(redirectedUrl(settings + "/calls"));
        mvc.perform(asManager(POST(settings + "/history")).with(csrf()).param("historySync", "RECENT"))
                .andExpect(redirectedUrl(settings + "/history"));

        // O POST de um grupo não desliga o que é de outro
        var saved = instances.findByName(name).orElseThrow();
        assertThat(saved.getSettings().rejectCall()).isTrue();
        assertThat(saved.getSettings().msgCall()).isEqualTo("Retorno depois");
        assertThat(saved.getSettings().readStatus()).isTrue();
        assertThat(saved.getSettings().groupsIgnore()).isTrue();
        assertThat(saved.getSettings().readMessages()).isFalse();
        assertThat(saved.getHistorySync()).isEqualTo(dev.msgforge.instance.HistorySync.RECENT);

        mvc.perform(asManager(GET(settings + "/calls")).flashAttr("saved", "calls"))
                .andExpect(content().string(containsString("Configurações salvas")))
                .andExpect(content().string(containsString("Retorno depois")))
                .andExpect(content().string(containsString("Recusa ligações")))
                .andExpect(content().string(containsString("2 de 3 ligadas")))
                .andExpect(content().string(containsString("Recente")));

        // Erro preso ao campo, com o que foi digitado
        var failed = mvc.perform(asManager(POST(settings + "/calls")).with(csrf())
                        .param("rejectCall", "false").param("msgCall", "x".repeat(4097)))
                .andExpect(redirectedUrl(settings + "/calls"))
                .andExpect(flash().attribute("fieldErrors",
                        java.util.Map.of("settings.msgCall", "manager.settings.error.msgCall.tooLong")))
                .andReturn();
        assertThat(instances.findByName(name).orElseThrow().getSettings().rejectCall()).isTrue();
        mvc.perform(asManager(GET(settings + "/calls"))
                        .flashAttr("fieldErrors", failed.getFlashMap().get("fieldErrors"))
                        .flashAttr("settings", failed.getFlashMap().get("settings")))
                .andExpect(content().string(containsString("id=\"msgCall-error\"")))
                .andExpect(content().string(containsString("4096 caracteres")));
    }

    @Test
    void proxyFormKeepsThePasswordHiddenAndShowsErrorsByTheField() throws Exception {
        var name = createInstance();
        var page = "/manager/instances/" + name + "/settings/proxy";
        mvc.perform(asManager(POST("/manager/instances/" + name + "/proxy")).with(csrf())
                        .param("enabled", "true").param("protocol", "socks5").param("host", "10.0.0.5")
                        .param("port", "1080").param("username", "cliente").param("password", "senha-secreta"))
                .andExpect(redirectedUrl(page))
                .andExpect(flash().attribute("saved", "proxy"));
        var encrypted = instances.findByName(name).orElseThrow().getProxy().orElseThrow().passwordEncrypted();
        assertThat(encrypted).isNotNull();

        mvc.perform(asManager(GET(page)))
                .andExpect(content().string(containsString("socks5://10.0.0.5:1080")))
                .andExpect(content().string(containsString("Há uma senha guardada")))
                .andExpect(content().string(Matchers.not(containsString("senha-secreta"))))
                .andExpect(content().string(Matchers.not(containsString(encrypted))));

        // Senha em branco, mesmo usuário: a guardada fica
        mvc.perform(asManager(POST("/manager/instances/" + name + "/proxy")).with(csrf())
                        .param("enabled", "true").param("protocol", "socks5").param("host", "10.0.0.6")
                        .param("port", "1080").param("username", "cliente").param("password", ""))
                .andExpect(flash().attribute("saved", "proxy"));
        assertThat(instances.findByName(name).orElseThrow().getProxy().orElseThrow().passwordEncrypted())
                .isEqualTo(encrypted);

        var failed = mvc.perform(asManager(POST("/manager/instances/" + name + "/proxy")).with(csrf())
                        .param("enabled", "true").param("protocol", "http").param("host", "10.0.0.7")
                        .param("port", "99999").param("username", "cliente"))
                .andExpect(redirectedUrl(page))
                .andExpect(flash().attribute("fieldErrors",
                        java.util.Map.of("proxy.port", "manager.proxy.error.port.invalid")))
                .andReturn();
        assertThat(instances.findByName(name).orElseThrow().getProxy().orElseThrow().host()).isEqualTo("10.0.0.6");

        var flashed = failed.getFlashMap();
        mvc.perform(asManager(GET(page))
                        .flashAttr("fieldErrors", flashed.get("fieldErrors")).flashAttr("proxy", flashed.get("proxy")))
                .andExpect(content().string(containsString("id=\"proxy-port-error\"")))
                .andExpect(content().string(containsString("A porta precisa ser um número de 1 a 65535.")))
                .andExpect(content().string(containsString("value=\"99999\"")))
                .andExpect(content().string(containsString("value=\"10.0.0.7\"")))
                // A aba mostra o que está guardado, não o digitado
                .andExpect(content().string(containsString("socks5://10.0.0.6:1080")));

        mvc.perform(asManager(POST("/manager/instances/" + name + "/proxy")).with(csrf()))
                .andExpect(flash().attribute("saved", "proxy"));
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
                // caiu depois de conectar: reconectar leva à tela Conexão
                .contains("href=\"/manager/instances/" + dropped + "/connection\"").contains("Reconectar")
                .contains("class=\"badge close\" data-state=\"close\"")
                // voltando sozinha: acompanhar no painel
                .contains("Ver conexão").contains("class=\"badge connecting\" data-state=\"connecting\"")
                .contains("4 instâncias").contains("1 conectada").contains("2 precisam de ação")
                // poucas instâncias: sem busca
                .doesNotContain("data-list-filter");
        assertThat(html("/manager/instances/" + dropped + "/connection")).contains("id=\"connect\"");
        assertThat(html("/manager/instances/" + dropped)).contains("Reconectar").doesNotContain("id=\"connect\"");
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
    void channelsAndIntegrationsAreAScreenOfEachInstanceAndComingSoonHasNoLinkOrButton() throws Exception {
        assertThat(html("/manager")).doesNotContain("soon-item").doesNotContain("Telegram");
        var name = createInstance();
        var page = html("/manager/instances/" + name + "/integrations");
        assertThat(page).contains("href=\"/manager/instances/" + name + "/integrations\" aria-current=\"page\"");

        // Canais: o WhatsApp Web em uso leva à conexão; Cloud API e Telegram só aparecem
        var start = page.indexOf("aria-labelledby=\"channels-title\"");
        assertThat(start).isPositive();
        var channels = page.substring(start, page.indexOf("</section>", start));
        assertThat(channels).contains("WhatsApp Web").contains("Em uso")
                .contains("href=\"/manager/instances/" + name + "/connection\"");
        var soon = channels.substring(channels.indexOf("soon-item"));
        assertThat(soon).contains("Cloud API oficial").contains("Telegram").contains("Em breve")
                .doesNotContain("<a ").doesNotContain("<button").doesNotContain("href=");

        // Integrações: só o bot com IA; as de terceiros saíram da tela (2026-11-16)
        assertThat(page).contains("Bot com IA")
                .doesNotContain("Typebot").doesNotContain("Chatwoot").doesNotContain("Dify")
                .doesNotContain("Flowise").doesNotContain("n8n");
    }

    @Test
    void themeChoiceIsKeptInACookieAndRenderedOnTheHtmlTag() throws Exception {
        // Sem escolha: segue o sistema, sem data-theme; o seletor fica no menu da conta, sem script inline
        var page = html("/manager");
        assertThat(page).doesNotContain("data-theme=").contains("action=\"/manager/theme\"")
                .contains("value=\"system\"").contains("value=\"light\"").contains("value=\"dark\"")
                .contains("aria-pressed=\"true\"").doesNotContain("onclick").doesNotContain("<script>");

        var saved = mvc.perform(asManager(POST("/manager/theme")).with(csrf())
                        .param("theme", "dark").param("back", "/manager/server"))
                .andExpect(redirectedUrl("/manager/server"))
                .andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(saved).startsWith("msgforge_theme=dark;").contains("Path=/manager").contains("HttpOnly")
                .contains("SameSite=Strict").contains("Max-Age=31536000");

        var dark = new jakarta.servlet.http.Cookie("msgforge_theme", "dark");
        mvc.perform(asManager(GET("/manager")).cookie(dark))
                .andExpect(content().string(containsString("data-theme=\"dark\"")));
        // A tela de login (sem sessão) também chega pintada com o tema escolhido
        mvc.perform(GET("/manager/login").cookie(new jakarta.servlet.http.Cookie("msgforge_theme", "light")))
                .andExpect(content().string(containsString("data-theme=\"light\"")));
        // Cookie adulterado vale como Sistema
        mvc.perform(asManager(GET("/manager")).cookie(new jakarta.servlet.http.Cookie("msgforge_theme", "\"><x")))
                .andExpect(content().string(Matchers.not(containsString("data-theme="))));

        // Voltar para o Sistema apaga o cookie
        var cleared = mvc.perform(asManager(POST("/manager/theme")).with(csrf()).cookie(dark).param("theme", "system"))
                .andExpect(redirectedUrl("/manager"))
                .andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(cleared).startsWith("msgforge_theme=;").contains("Max-Age=0");
    }

    @Test
    void themeChangeOnlyReturnsToManagerPagesAndNeedsCsrfAndSession() throws Exception {
        for (var outside : List.of("https://exemplo.com", "//exemplo.com/manager", "/manager//exemplo.com",
                "/api/instance", "/manager\\evil", "/managerx")) {
            mvc.perform(asManager(POST("/manager/theme")).with(csrf()).param("theme", "light").param("back", outside))
                    .andExpect(redirectedUrl("/manager"));
        }
        mvc.perform(asManager(POST("/manager/theme")).param("theme", "light")).andExpect(status().isForbidden());
        mvc.perform(POST("/manager/theme").with(csrf()).param("theme", "light"))
                .andExpect(redirectedUrl("/manager/login"));
    }

    @Test
    void logoutIsDisabledWhileDisconnectedAndTheAvatarOnlyExistsForAPairedAccount() throws Exception {
        var name = createInstance();
        var panel = "/manager/instances/" + name;
        mvc.perform(asManager(GET(panel + "/connection")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Já está desconectada")))
                .andExpect(content().string(containsString("disabled=\"disabled\"")));
        // Nunca pareada: as iniciais no lugar da foto (sem pedir a foto), na Visão geral e na lista; a rota responde 404
        var avatarSrc = "src=\"/manager/instances/" + name + "/avatar\"";
        mvc.perform(asManager(GET(panel)))
                .andExpect(content().string(containsString("class=\"avatar avatar-lg\"")))
                .andExpect(content().string(containsString("class=\"avatar-initials\"")))
                .andExpect(content().string(containsString("Nenhum número conectado ainda")))
                .andExpect(content().string(Matchers.not(containsString(avatarSrc))));
        mvc.perform(asManager(GET("/manager")))
                .andExpect(content().string(containsString("class=\"avatar avatar-sm\"")))
                .andExpect(content().string(Matchers.not(containsString(avatarSrc))));
        mvc.perform(asManager(GET(panel + "/avatar"))).andExpect(status().isNotFound());
    }
}

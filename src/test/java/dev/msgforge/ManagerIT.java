package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(content().string(Matchers.not(Matchers.containsString(token))));
        mvc.perform(asManager(GET("/manager")))
                .andExpect(view().name("manager/instances"))
                .andExpect(content().string(Matchers.containsString(name)))
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
}

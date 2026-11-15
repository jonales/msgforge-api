// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.msgforge.access.AccessAdminService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Organization;
import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.support.AccessFixtures;
import dev.msgforge.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Assinatura do atendente nas respostas do Manager e o registro de quem enviou cada mensagem. */
@AutoConfigureMockMvc
class AttendantSignatureIT extends IntegrationTest {

    private static final String JID = "5561933334444@s.whatsapp.net";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccessAdminService admin;

    @Autowired
    InstanceRepository instances;

    private ManagerPrincipal ana;

    @BeforeEach
    @AfterEach
    void resetAccess() {
        AccessFixtures.reset(jdbc);
    }

    @BeforeEach
    void createAna() {
        var user = admin.createUser(ManagerPrincipal.GLOBAL_KEY, Organization.DEFAULT_ID, "ana@exemplo.com",
                "  Ana *Souza*  ", "senha-forte-123", AccessFixtures.GESTOR, true);
        ana = new ManagerPrincipal(user.getId(), user.getEmail());
    }

    private static MockHttpServletRequestBuilder as(ManagerPrincipal who, MockHttpServletRequestBuilder request) {
        return request.with(authentication(UsernamePasswordAuthenticationToken.authenticated(
                who, null, List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))))).with(csrf());
    }

    private void reply(ManagerPrincipal who, String instance, String text) throws Exception {
        mvc.perform(as(who, MockMvcRequestBuilders.post("/manager/instances/" + instance + "/messages/reply"))
                        .param("jid", JID).param("text", text))
                .andExpect(status().is3xxRedirection());
    }

    /** Texto, sent_by e sent_by_name da última mensagem enviada para a conversa. */
    private Map<String, Object> last(String instance) {
        return jdbc.queryForMap("""
                SELECT m.message ->> 'conversation' AS text, m.sent_by, m.sent_by_user_id, m.sent_by_name
                FROM message m JOIN instance i ON i.id = m.instance_id
                WHERE i.name = ? AND m.from_me ORDER BY m.created_at DESC LIMIT 1""", instance);
    }

    @Test
    void repliesAreSignedOnlyWhenTheInstanceSignsAndThePersonHasAName() throws Exception {
        var name = uniqueName("assina");
        mvc.perform(as(ana, MockMvcRequestBuilders.post("/manager/instances")).param("instanceName", name))
                .andExpect(status().is3xxRedirection());

        // Desligada (padrão): sai como foi escrita, mas o histórico sabe quem mandou
        reply(ana, name, "primeira");
        assertThat(last(name)).containsEntry("text", "primeira").containsEntry("sent_by", "USER")
                .containsEntry("sent_by_user_id", ana.userId()).containsEntry("sent_by_name", "Ana Souza");

        // Liga no grupo "Atendimento"; a aba mostra o estado
        mvc.perform(as(ana, MockMvcRequestBuilders.post("/manager/instances/" + name + "/settings/attendance"))
                        .param("signMessages", "true"))
                .andExpect(flash().attribute("saved", "attendance"));
        assertThat(instances.findByName(name).orElseThrow().isSignMessages()).isTrue();
        mvc.perform(as(ana, MockMvcRequestBuilders.get("/manager/instances/" + name + "/settings/attendance")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"signMessages\"")))
                .andExpect(content().string(containsString("Assinando")));

        // Nome em negrito (sem os * que a pessoa digitou no nome) e a mensagem embaixo
        reply(ana, name, "tudo certo?");
        assertThat(last(name)).containsEntry("text", "*Atendente Ana Souza:*\ntudo certo?");

        // Outro modelo de assinatura; sem {nome}, recusa perto do campo
        mvc.perform(as(ana, MockMvcRequestBuilders.post("/manager/instances/" + name + "/settings/attendance"))
                        .param("signMessages", "true").param("signatureTemplate", "Suporte"))
                .andExpect(flash().attribute("fieldErrors",
                        Map.of("settings.signatureTemplate", "manager.settings.error.signatureTemplate.invalid")));
        mvc.perform(as(ana, MockMvcRequestBuilders.post("/manager/instances/" + name + "/settings/attendance"))
                        .param("signMessages", "true").param("signatureTemplate", "{nome} (Suporte)"))
                .andExpect(flash().attribute("saved", "attendance"));
        reply(ana, name, "outra");
        assertThat(last(name)).containsEntry("text", "*Ana Souza (Suporte):*\noutra");

        // A conversa mostra quem enviou
        mvc.perform(as(ana, MockMvcRequestBuilders.get("/manager/instances/" + name + "/messages")).param("jid", JID))
                .andExpect(content().string(containsString("por Ana Souza")));

        // Chave global: sem assinatura
        reply(ManagerPrincipal.GLOBAL_KEY, name, "pela chave");
        assertThat(last(name)).containsEntry("text", "pela chave").containsEntry("sent_by", "MANAGER_KEY");

        // Envio de teste: nunca assina, mas guarda o autor
        mvc.perform(as(ana, MockMvcRequestBuilders.post("/manager/instances/" + name + "/send"))
                        .param("number", "5561933334444").param("text", "teste"))
                .andExpect(status().is3xxRedirection());
        assertThat(last(name)).containsEntry("text", "teste").containsEntry("sent_by", "USER");

        // Sem nome de exibição: responde sem assinatura (e o histórico usa o e-mail)
        mvc.perform(as(ana, MockMvcRequestBuilders.post("/manager/users/" + ana.userId() + "/name"))
                        .param("org", Organization.DEFAULT_ID.toString()).param("displayName", ""))
                .andExpect(flash().attribute("success", "manager.users.nameChanged"));
        reply(ana, name, "sem nome");
        assertThat(last(name)).containsEntry("text", "sem nome").containsEntry("sent_by_name", "ana@exemplo.com");

        // Nome longo demais: recusa e mantém o atual
        mvc.perform(as(ana, MockMvcRequestBuilders.post("/manager/users/" + ana.userId() + "/name"))
                        .param("org", Organization.DEFAULT_ID.toString()).param("displayName", "x".repeat(61)))
                .andExpect(flash().attribute("error", "manager.access.error.displayName"));
    }

    @Test
    void apiSendsRecordWhetherTheGlobalKeyOrTheInstanceTokenWasUsed() {
        var name = uniqueName("api");
        assertThat(post("/instance/create", "{\"instanceName\":\"" + name + "\"}").status()).isEqualTo(201);
        var token = instances.findByName(name).orElseThrow().getToken();

        assertThat(post("/message/sendText/" + name, "{\"number\":\"5561933334444\",\"text\":\"chave\"}").status())
                .isEqualTo(201);
        assertThat(last(name)).containsEntry("sent_by", "API_KEY").containsEntry("sent_by_user_id", null);

        assertThat(call("POST", "/message/sendText/" + name, token,
                "{\"number\":\"5561933334444\",\"text\":\"token\"}").status()).isEqualTo(201);
        assertThat(last(name)).containsEntry("text", "token").containsEntry("sent_by", "API_TOKEN");
    }
}

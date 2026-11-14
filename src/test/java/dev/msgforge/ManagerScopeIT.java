package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.msgforge.access.AccessAdminService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Organization;
import dev.msgforge.support.AccessFixtures;
import dev.msgforge.support.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Cada pessoa vê e opera só o que o papel permite (ADR 0010, fatia 5): a lista, o painel e cada
 * ação do Manager conferem a permissão do catálogo na instância. Três instâncias: {@code mine} e
 * {@code sibling} na organização padrão, {@code foreign} em outra.
 */
@AutoConfigureMockMvc
class ManagerScopeIT extends IntegrationTest {

    private static final String PASSWORD = "senha-inicial-123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessAdminService admin;

    String mine;
    String sibling;
    String foreign;
    UUID otherOrg;

    @BeforeEach
    void instancesInTwoOrganizations() {
        AccessFixtures.reset(jdbc);
        mine = createInstance("minha");
        sibling = createInstance("vizinha");
        foreign = createInstance("alheia");
        otherOrg = admin.createOrganization(ManagerPrincipal.GLOBAL_KEY, "Outra " + UUID.randomUUID()).getId();
        jdbc.update("UPDATE instance SET organization_id = ? WHERE name = ?", otherOrg, foreign);
    }

    @AfterEach
    void resetAccess() {
        AccessFixtures.reset(jdbc);
    }

    private String createInstance(String prefix) {
        var name = uniqueName(prefix);
        assertThat(post("/instance/create", """
                {"instanceName":"%s"}""".formatted(name)).status()).isEqualTo(201);
        return name;
    }

    private UUID createUser(String email, UUID organizationId, UUID roleId) {
        return admin.createUser(ManagerPrincipal.GLOBAL_KEY, organizationId, email, PASSWORD, roleId, false).getId();
    }

    /** O vínculo da pessoa passa a valer só para {@code instanceName} (escopo por instância). */
    private void limitTo(UUID userId, String instanceName) {
        jdbc.update("UPDATE membership SET all_instances = false WHERE user_id = ?", userId);
        jdbc.update("INSERT INTO membership_instance (membership_id, instance_id) "
                + "SELECT m.id, i.id FROM membership m, instance i WHERE m.user_id = ? AND i.name = ?", userId, instanceName);
    }

    private MockHttpSession login(String email) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf())
                        .param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/manager")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession loginByKey() throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf())
                        .param("username", "manager").param("apikey", API_KEY))
                .andExpect(redirectedUrl("/manager")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static String panel(String name) {
        return "/manager/instances/" + name;
    }

    @Test
    void theClientSeesOnlyItsInstanceAndOnlyConnects() throws Exception {
        var id = createUser("cliente@exemplo.com", Organization.DEFAULT_ID, AccessFixtures.CLIENTE);
        limitTo(id, mine);
        var client = login("cliente@exemplo.com");

        // A lista e o trecho ao vivo: só a instância do escopo, e o resumo conta só ela
        for (var path : new String[] {"/manager", "/manager/instances/table"}) {
            mvc.perform(MockMvcRequestBuilders.get(path).session(client)).andExpect(status().isOk())
                    .andExpect(content().string(containsString(">" + mine + "<")))
                    .andExpect(content().string(not(containsString(sibling))))
                    .andExpect(content().string(not(containsString(foreign))))
                    .andExpect(content().string(containsString("1 instância")))
                    // Pode conectar: a ação da linha (nunca conectada) é continuar a conexão
                    .andExpect(content().string(containsString(panel(mine) + "/connect")))
                    .andExpect(content().string(not(containsString("href=\"/manager/instances/new\""))));
        }

        // O painel mostra o que o papel usa (conectar) e esconde o resto
        mvc.perform(MockMvcRequestBuilders.get(panel(mine)).session(client)).andExpect(status().isOk())
                .andExpect(content().string(containsString("action=\"" + panel(mine) + "/connect\"")))
                .andExpect(content().string(containsString("action=\"" + panel(mine) + "/logout\"")))
                .andExpect(content().string(not(containsString(panel(mine) + "/messages"))))
                .andExpect(content().string(not(containsString(panel(mine) + "/webhook"))))
                .andExpect(content().string(not(containsString(panel(mine) + "/settings"))))
                .andExpect(content().string(not(containsString(panel(mine) + "/proxy"))))
                .andExpect(content().string(not(containsString(panel(mine) + "/send"))))
                .andExpect(content().string(not(containsString(panel(mine) + "/delete"))));

        // Conectar e ver o QR
        mvc.perform(MockMvcRequestBuilders.post(panel(mine) + "/connect").session(client).with(csrf()))
                .andExpect(redirectedUrl(panel(mine)));
        mvc.perform(MockMvcRequestBuilders.get(panel(mine) + "/live").session(client))
                .andExpect(status().isOk()).andExpect(content().string(containsString("stub-qr-")));
        mvc.perform(MockMvcRequestBuilders.get(panel(mine) + "/connect").session(client))
                .andExpect(status().isOk()).andExpect(view().name("manager/connect"));

        // O que o papel não inclui: 403 com a tela "Sem acesso", mesmo num POST montado à mão
        mvc.perform(MockMvcRequestBuilders.post(panel(mine) + "/settings").session(client).with(csrf())
                        .param("rejectCall", "true"))
                .andExpect(status().isForbidden()).andExpect(view().name("manager/forbidden"));
        mvc.perform(MockMvcRequestBuilders.post(panel(mine) + "/webhook").session(client).with(csrf())
                        .param("url", "https://exemplo.com/hook"))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get(panel(mine) + "/messages").session(client))
                .andExpect(status().isForbidden()).andExpect(view().name("manager/forbidden"));
        mvc.perform(MockMvcRequestBuilders.post(panel(mine) + "/send").session(client).with(csrf())
                        .param("number", "5561999999999").param("text", "oi"))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post(panel(mine) + "/delete").session(client).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(get("/instance/connectionState/" + mine).status()).isEqualTo(200);   // não foi excluída
        mvc.perform(MockMvcRequestBuilders.get("/manager/instances/new").session(client))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/manager/instances").session(client).with(csrf())
                        .param("instanceName", uniqueName("intrusa")))
                .andExpect(status().isForbidden());

        // Outra organização e fora do escopo: 404, igual a uma que não existe
        for (var hidden : new String[] {foreign, sibling, "nao-existe"}) {
            mvc.perform(MockMvcRequestBuilders.get(panel(hidden)).session(client))
                    .andExpect(status().isNotFound()).andExpect(view().name("manager/not-found"));
            mvc.perform(MockMvcRequestBuilders.post(panel(hidden) + "/connect").session(client).with(csrf()))
                    .andExpect(status().isNotFound());
            mvc.perform(MockMvcRequestBuilders.get(panel(hidden) + "/live").session(client))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void aViewerWithoutConnectSeesTheStateButNotTheQr() throws Exception {
        createUser("atendente@exemplo.com", Organization.DEFAULT_ID, AccessFixtures.ATENDENTE);
        var viewer = login("atendente@exemplo.com");
        assertThat(get("/instance/connect/" + mine).status()).isEqualTo(200);

        // Na lista, a linha mostra só o estado (sem "continuar a conexão")
        mvc.perform(MockMvcRequestBuilders.get("/manager/instances/table").session(viewer))
                .andExpect(content().string(containsString(">" + mine + "<")))
                .andExpect(content().string(not(containsString(panel(mine) + "/connect"))));
        mvc.perform(MockMvcRequestBuilders.get(panel(mine) + "/live").session(viewer))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("stub-qr-"))))
                .andExpect(content().string(containsString("só para quem pode conectar")));
        mvc.perform(MockMvcRequestBuilders.get(panel(mine)).session(viewer))
                .andExpect(content().string(not(containsString("stub-qr-"))))
                .andExpect(content().string(containsString(panel(mine) + "/messages")))
                .andExpect(content().string(not(containsString("action=\"" + panel(mine) + "/connect\""))));
        mvc.perform(MockMvcRequestBuilders.get(panel(mine) + "/connect").session(viewer))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get(panel(mine) + "/messages").session(viewer))
                .andExpect(status().isOk());
    }

    @Test
    void theManagerSeesTheWholeOrganizationAndTheKeySeesEverything() throws Exception {
        createUser("gestor@exemplo.com", Organization.DEFAULT_ID, AccessFixtures.GESTOR);
        var boss = login("gestor@exemplo.com");
        mvc.perform(MockMvcRequestBuilders.get("/manager").session(boss))
                .andExpect(content().string(containsString(mine)))
                .andExpect(content().string(containsString(sibling)))
                .andExpect(content().string(not(containsString(foreign))))
                .andExpect(content().string(containsString("href=\"/manager/instances/new\"")));
        mvc.perform(MockMvcRequestBuilders.get(panel(sibling)).session(boss)).andExpect(status().isOk())
                .andExpect(content().string(containsString(panel(sibling) + "/webhook")))
                .andExpect(content().string(containsString(panel(sibling) + "/settings")))
                .andExpect(content().string(containsString(panel(sibling) + "/delete")));
        mvc.perform(MockMvcRequestBuilders.get(panel(foreign)).session(boss)).andExpect(status().isNotFound());
        mvc.perform(MockMvcRequestBuilders.post(panel(sibling) + "/delete").session(boss).with(csrf()))
                .andExpect(redirectedUrl("/manager"));

        var key = loginByKey();
        mvc.perform(MockMvcRequestBuilders.get("/manager").session(key))
                .andExpect(content().string(containsString(mine)))
                .andExpect(content().string(containsString(foreign)));
        mvc.perform(MockMvcRequestBuilders.get(panel(foreign)).session(key)).andExpect(status().isOk())
                .andExpect(content().string(containsString(panel(foreign) + "/delete")));
    }

    @Test
    void aChangedRoleCountsOnTheNextRequest() throws Exception {
        var id = createUser("ana@exemplo.com", Organization.DEFAULT_ID, AccessFixtures.CLIENTE);
        var session = login("ana@exemplo.com");
        mvc.perform(MockMvcRequestBuilders.get(panel(mine) + "/messages").session(session))
                .andExpect(status().isForbidden());

        admin.changeRole(ManagerPrincipal.GLOBAL_KEY, Organization.DEFAULT_ID, id, AccessFixtures.ATENDENTE);

        // A mesma sessão, sem novo login
        mvc.perform(MockMvcRequestBuilders.get(panel(mine) + "/messages").session(session))
                .andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.post(panel(mine) + "/connect").session(session).with(csrf()))
                .andExpect(status().isForbidden());

        // E o escopo também: fora dele, a instância some
        limitTo(id, sibling);
        mvc.perform(MockMvcRequestBuilders.get(panel(mine) + "/messages").session(session))
                .andExpect(status().isNotFound());
    }

    @Test
    void theGuidedCreationPutsTheInstanceInTheCreatorsOrganization() throws Exception {
        var gestor = jdbc.queryForObject("SELECT id FROM role WHERE organization_id = ? AND name = 'Gestor'",
                UUID.class, otherOrg);
        createUser("outra@exemplo.com", otherOrg, gestor);
        var session = login("outra@exemplo.com");

        // Uma organização só: o passo 1 não pergunta
        mvc.perform(MockMvcRequestBuilders.get("/manager/instances/new").session(session))
                .andExpect(status().isOk()).andExpect(content().string(not(containsString("name=\"org\""))));
        var name = uniqueName("guiada");
        mvc.perform(MockMvcRequestBuilders.post("/manager/instances/new").session(session).with(csrf())
                        .param("instanceName", name)
                        // Pedir uma organização em que não pode criar não muda o destino: é recusado
                        .param("org", Organization.DEFAULT_ID.toString()))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/manager/instances/new").session(session).with(csrf())
                        .param("instanceName", name))
                .andExpect(redirectedUrl("/manager/instances/new/connection"));
        mvc.perform(MockMvcRequestBuilders.post("/manager/instances/new/connection").session(session).with(csrf())
                        .param("method", "qr"))
                .andExpect(redirectedUrl("/manager/instances/new/history"));
        mvc.perform(MockMvcRequestBuilders.post("/manager/instances/new/history").session(session).with(csrf())
                        .param("historySync", "NONE"))
                .andExpect(redirectedUrl(panel(name) + "/connect"));

        assertThat(jdbc.queryForObject("SELECT organization_id FROM instance WHERE name = ?", UUID.class, name))
                .isEqualTo(otherOrg);
        mvc.perform(MockMvcRequestBuilders.get("/manager").session(session))
                .andExpect(content().string(containsString(name)))
                .andExpect(content().string(containsString(foreign)))
                .andExpect(content().string(not(containsString(mine))));

        // A chave global (admin da plataforma) cria em qualquer uma: o passo 1 pergunta em qual
        mvc.perform(MockMvcRequestBuilders.get("/manager/instances/new").session(loginByKey()))
                .andExpect(content().string(containsString("name=\"org\"")))
                .andExpect(content().string(containsString("value=\"" + otherOrg + "\"")));
    }
}

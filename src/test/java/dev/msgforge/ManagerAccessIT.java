package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Login do Manager por e-mail e senha e pela chave global, e a tela de usuários (ADR 0010). */
@AutoConfigureMockMvc
class ManagerAccessIT extends IntegrationTest {

    private static final String PASSWORD = "senha-inicial-123";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessAdminService admin;

    @BeforeEach
    @AfterEach
    void resetAccess() {
        AccessFixtures.reset(jdbc);
    }

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private UUID createUser(String email, UUID roleId) {
        return admin.createUser(ManagerPrincipal.GLOBAL_KEY, Organization.DEFAULT_ID, email, PASSWORD, roleId, false)
                .getId();
    }

    private MockHttpSession loginByEmail(String email, String password) throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf())
                        .param("username", email).param("password", password))
                .andExpect(redirectedUrl("/manager")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private MockHttpSession loginByKey() throws Exception {
        var result = mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf())
                        .param("username", "manager").param("apikey", API_KEY))
                .andExpect(redirectedUrl("/manager")).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static ManagerPrincipal principalOf(MockHttpSession session) {
        var context = (SecurityContext) session.getAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        return (ManagerPrincipal) context.getAuthentication().getPrincipal();
    }

    @Test
    void firstAccessShowsOnlyTheKeyAndThenTheEmailForm() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/manager/login"))
                .andExpect(content().string(containsString("name=\"apikey\"")))
                .andExpect(content().string(not(containsString("name=\"password\""))));

        createUser("ana@exemplo.com", AccessFixtures.GESTOR);

        mvc.perform(MockMvcRequestBuilders.get("/manager/login"))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(containsString("type=\"email\"")))
                // MANAGER_KEY_LOGIN=true (padrão): a chave continua como alternativa
                .andExpect(content().string(containsString("name=\"apikey\"")));
    }

    @Test
    void emailLoginRecordsWhoIsLoggedIn() throws Exception {
        var id = createUser("ana@exemplo.com", AccessFixtures.OPERADOR);

        var session = loginByEmail("  ANA@exemplo.com ", PASSWORD);   // e-mail sem diferenciar maiúsculas

        assertThat(principalOf(session)).isEqualTo(new ManagerPrincipal(id, "ana@exemplo.com"));
        assertThat(jdbc.queryForObject("SELECT last_login_at IS NOT NULL FROM app_user WHERE id = ?", Boolean.class, id))
                .isTrue();
        mvc.perform(MockMvcRequestBuilders.get("/manager").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("ana@exemplo.com")));
    }

    @Test
    void theGlobalKeyStillEntersWithUsersAround() throws Exception {
        createUser("ana@exemplo.com", AccessFixtures.GESTOR);
        assertThat(principalOf(loginByKey()).globalKey()).isTrue();
    }

    @Test
    void wrongPasswordsCountInTheLimiterAndThenEvenTheRightOneIsBlocked() throws Exception {
        createUser("ana@exemplo.com", AccessFixtures.GESTOR);
        var attacker = from("203.0.113.88");
        for (int i = 0; i < 10; i++) {
            mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf()).with(attacker)
                            .param("username", i % 2 == 0 ? "ana@exemplo.com" : "ninguem@exemplo.com")
                            .param("password", "chute-" + i))
                    .andExpect(redirectedUrl("/manager/login?error"));
        }
        mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf()).with(attacker)
                        .param("username", "ana@exemplo.com").param("password", PASSWORD))
                .andExpect(redirectedUrl("/manager/login?blocked"));
        // De outro endereço, entra
        loginByEmail("ana@exemplo.com", PASSWORD);
    }

    @Test
    void aBlockedUserDoesNotEnterAndLosesTheOpenSession() throws Exception {
        var id = createUser("ana@exemplo.com", AccessFixtures.OPERADOR);
        var page = loginByEmail("ana@exemplo.com", PASSWORD);
        var polling = loginByEmail("ana@exemplo.com", PASSWORD);

        createUser("gestor@exemplo.com", AccessFixtures.GESTOR);
        admin.setBlocked(ManagerPrincipal.GLOBAL_KEY, Organization.DEFAULT_ID, id, true);

        // As sessões abertas caem na próxima requisição
        mvc.perform(MockMvcRequestBuilders.get("/manager").session(page))
                .andExpect(redirectedUrl("/manager/login"));
        mvc.perform(MockMvcRequestBuilders.get("/manager/instances/table").session(polling).header("HX-Request", "true"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("HX-Redirect", "/manager/login"));
        // Com a senha certa, não entra (e o aviso é outro)
        mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf()).with(from("198.51.100.9"))
                        .param("username", "ana@exemplo.com").param("password", PASSWORD))
                .andExpect(redirectedUrl("/manager/login?disabled"));
        // Senha errada de um bloqueado continua sendo só "incorreto" (não revela o bloqueio)
        mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf()).with(from("198.51.100.9"))
                        .param("username", "ana@exemplo.com").param("password", "errada-errada"))
                .andExpect(redirectedUrl("/manager/login?error"));
    }

    @Test
    void theUsersScreenRequiresManagingUsers() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/manager/users")).andExpect(redirectedUrl("/manager/login"));

        createUser("cliente@exemplo.com", AccessFixtures.CLIENTE);
        var client = loginByEmail("cliente@exemplo.com", PASSWORD);
        mvc.perform(MockMvcRequestBuilders.get("/manager/users").session(client))
                .andExpect(status().isForbidden()).andExpect(view().name("manager/forbidden"));
        mvc.perform(MockMvcRequestBuilders.get("/manager/roles").session(client)).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/manager/users").session(client).with(csrf())
                        .param("org", Organization.DEFAULT_ID.toString()).param("email", "x@exemplo.com")
                        .param("password", PASSWORD).param("roleId", AccessFixtures.CLIENTE.toString()))
                .andExpect(status().isForbidden());
        // Sem a permissão, o topo não mostra "Usuários"
        mvc.perform(MockMvcRequestBuilders.get("/manager").session(client))
                .andExpect(content().string(not(containsString("href=\"/manager/users\""))));

        createUser("gestor@exemplo.com", AccessFixtures.GESTOR);
        var boss = loginByEmail("gestor@exemplo.com", PASSWORD);
        mvc.perform(MockMvcRequestBuilders.get("/manager").session(boss))
                .andExpect(content().string(containsString("href=\"/manager/users\"")));
        mvc.perform(MockMvcRequestBuilders.get("/manager/users").session(boss))
                .andExpect(status().isOk()).andExpect(view().name("manager/users"))
                .andExpect(content().string(containsString("cliente@exemplo.com")));
    }

    @Test
    void theKeyCreatesTheFirstUserWhoThenLogsIn() throws Exception {
        var key = loginByKey();
        var org = Organization.DEFAULT_ID.toString();

        // Sem CSRF, nada
        mvc.perform(MockMvcRequestBuilders.post("/manager/users").session(key)
                        .param("org", org).param("email", "primeira@exemplo.com").param("password", PASSWORD)
                        .param("roleId", AccessFixtures.GESTOR.toString()).param("platformAdmin", "true"))
                .andExpect(status().isForbidden());
        // Senha curta: volta à tela com o erro e o e-mail digitado
        mvc.perform(MockMvcRequestBuilders.post("/manager/users").session(key).with(csrf())
                        .param("org", org).param("email", "primeira@exemplo.com").param("password", "curta")
                        .param("roleId", AccessFixtures.GESTOR.toString()))
                .andExpect(status().isOk()).andExpect(view().name("manager/users"))
                .andExpect(content().string(containsString("A senha precisa ter")))
                .andExpect(content().string(containsString("value=\"primeira@exemplo.com\"")));

        mvc.perform(MockMvcRequestBuilders.post("/manager/users").session(key).with(csrf())
                        .param("org", org).param("email", "primeira@exemplo.com").param("password", PASSWORD)
                        .param("roleId", AccessFixtures.GESTOR.toString()).param("platformAdmin", "true"))
                .andExpect(redirectedUrl("/manager/users?org=" + org));

        var first = loginByEmail("primeira@exemplo.com", PASSWORD);
        mvc.perform(MockMvcRequestBuilders.get("/manager/users").session(first)).andExpect(status().isOk())
                .andExpect(content().string(containsString("admin da plataforma")));
    }

    @Test
    void rolesAreEditedByTheOrganization() throws Exception {
        var key = loginByKey();
        var org = Organization.DEFAULT_ID.toString();

        mvc.perform(MockMvcRequestBuilders.get("/manager/roles").session(key))
                .andExpect(status().isOk()).andExpect(view().name("manager/roles"))
                .andExpect(content().string(containsString("Cliente")));
        mvc.perform(MockMvcRequestBuilders.get("/manager/roles/new").session(key).param("org", org))
                .andExpect(status().isOk()).andExpect(view().name("manager/role"));
        mvc.perform(MockMvcRequestBuilders.post("/manager/roles").session(key).with(csrf()).param("org", org)
                        .param("name", "Só QR").param("permissions", "INSTANCE_VIEW", "INSTANCE_CONNECT"))
                .andExpect(redirectedUrl("/manager/roles?org=" + org));
        var id = jdbc.queryForObject("SELECT id FROM role WHERE name = 'Só QR'", UUID.class);
        mvc.perform(MockMvcRequestBuilders.get("/manager/roles/" + id).session(key).param("org", org))
                .andExpect(status().isOk()).andExpect(content().string(containsString("value=\"Só QR\"")));
        // Permissão fora do catálogo é recusada
        mvc.perform(MockMvcRequestBuilders.post("/manager/roles/" + id).session(key).with(csrf()).param("org", org)
                        .param("name", "Só QR").param("permissions", "SUPERPODER"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("Permissão desconhecida")));
        mvc.perform(MockMvcRequestBuilders.post("/manager/roles/" + id + "/delete").session(key).with(csrf())
                        .param("org", org))
                .andExpect(redirectedUrl("/manager/roles?org=" + org));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM role WHERE id = ?", Integer.class, id)).isZero();
    }
}

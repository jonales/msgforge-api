package dev.msgforge;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import dev.msgforge.access.AccessAdminService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Organization;
import dev.msgforge.support.AccessFixtures;
import dev.msgforge.support.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Com {@code MANAGER_KEY_LOGIN=false}, a chave global só entra no primeiro acesso (sem usuários),
 * como a ADR 0010 descreve. Contexto próprio por causa da propriedade.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "msgforge.manager.key-login=false")
class ManagerKeyLoginOffIT extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessAdminService admin;

    @BeforeEach
    @AfterEach
    void resetAccess() {
        AccessFixtures.reset(jdbc);
    }

    private org.springframework.test.web.servlet.ResultActions keyLogin() throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/manager/login").with(csrf())
                .param("username", "manager").param("apikey", API_KEY));
    }

    @Test
    void theKeyOnlyEntersWhileThereAreNoUsers() throws Exception {
        keyLogin().andExpect(redirectedUrl("/manager"));

        admin.createUser(ManagerPrincipal.GLOBAL_KEY, Organization.DEFAULT_ID, "ana@exemplo.com",
                "senha-inicial-123", AccessFixtures.GESTOR, true);

        keyLogin().andExpect(redirectedUrl("/manager/login?error"));
        mvc.perform(MockMvcRequestBuilders.get("/manager/login"))
                .andExpect(content().string(not(containsString("name=\"apikey\""))));
    }
}

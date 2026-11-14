package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import dev.msgforge.access.AccessAdminService;
import dev.msgforge.access.ManagerPrincipal;
import dev.msgforge.access.Organization;
import dev.msgforge.support.AccessFixtures;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import dev.msgforge.notification.Notification;
import dev.msgforge.notification.NotificationService;
import dev.msgforge.support.IntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Tela Servidor sem SMTP_HOST (o padrão): a aplicação sobe, a tela diz que o e-mail está desligado. */
@AutoConfigureMockMvc
class ServerPageIT extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void requiresTheManagerSession() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/manager/server"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/manager/login"));
        mvc.perform(MockMvcRequestBuilders.post("/manager/server/test-email").with(csrf()).param("email", "a@b.com"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/manager/login"));
    }

    @Test
    void withoutSmtpTheScreenSaysEmailIsOffAndHidesTheTestForm() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/manager/server").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/server"))
                .andExpect(content().string(Matchers.containsString("Desligado")))
                .andExpect(content().string(Matchers.containsString("SMTP_HOST")))
                .andExpect(content().string(Matchers.not(Matchers.containsString("name=\"email\""))))
                .andExpect(content().string(Matchers.containsString("aria-current=\"page\"")));
    }

    @Test
    void withoutSmtpNothingIsQueued() throws Exception {
        assertThat(notifications.isEmailEnabled()).isFalse();
        var to = uniqueName("sem-smtp") + "@exemplo.com";

        assertThat(notifications.send(Notification.test(to))).isEqualTo(NotificationService.Result.DISABLED);
        mvc.perform(MockMvcRequestBuilders.post("/manager/server/test-email").with(admin())
                        .with(csrf()).param("email", to))
                .andExpect(redirectedUrl("/manager/server"))
                .andExpect(flash().attribute("error", "manager.server.test.disabled"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE order_key = ?", Long.class, to)).isZero();
    }

    @Test
    void onlyThePlatformAdminSeesTheServerPage(@Autowired AccessAdminService admin, @Autowired JdbcTemplate jdbc)
            throws Exception {
        try {
            var operator = admin.createUser(ManagerPrincipal.GLOBAL_KEY, Organization.DEFAULT_ID,
                    "operador@exemplo.com", "senha-inicial-123", AccessFixtures.OPERADOR, false);
            var ordinary = new UsernamePasswordAuthenticationToken(
                    new ManagerPrincipal(operator.getId(), "operador@exemplo.com"), null,
                    List.of(new SimpleGrantedAuthority("ROLE_MANAGER")));
            mvc.perform(MockMvcRequestBuilders.get("/manager/server").with(authentication(ordinary)))
                    .andExpect(status().isForbidden());
        } finally {
            AccessFixtures.reset(jdbc);
        }
    }

    /** Admin da plataforma (a chave global): a tela Servidor é só dele. */
    private static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(ManagerPrincipal.GLOBAL_KEY, null,
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
    }
}

package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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
        mvc.perform(MockMvcRequestBuilders.get("/manager/server").with(user("manager").roles("MANAGER")))
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
        mvc.perform(MockMvcRequestBuilders.post("/manager/server/test-email").with(user("manager").roles("MANAGER"))
                        .with(csrf()).param("email", to))
                .andExpect(redirectedUrl("/manager/server"))
                .andExpect(flash().attribute("error", "manager.server.test.disabled"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE order_key = ?", Long.class, to)).isZero();
    }
}

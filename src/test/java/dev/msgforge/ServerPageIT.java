// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
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
import dev.msgforge.notification.EmailSender;
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
                .andExpect(content().string(Matchers.containsString("SMTP_*")))
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

    @Test
    void smtpSavedHereWinsAndGoesBackToTheEnvironment() throws Exception {
        try {
            mvc.perform(MockMvcRequestBuilders.post("/manager/server/smtp").with(admin()).with(csrf())
                            .param("action", "save").param("host", "127.0.0.1").param("port", "2525")
                            .param("from", "Msgforge <avisos@msgforge.test>").param("timeoutMs", "2000"))
                    .andExpect(redirectedUrl("/manager/server"))
                    .andExpect(flash().attribute("success", "manager.server.smtp.saved"));

            // Vale na hora, sem reiniciar
            assertThat(notifications.isEmailEnabled()).isTrue();
            assertThat(notifications.emailStatus().source()).isEqualTo(EmailSender.Source.DATABASE);
            mvc.perform(MockMvcRequestBuilders.get("/manager/server").with(admin()))
                    .andExpect(content().string(Matchers.containsString("definida aqui")))
                    .andExpect(content().string(Matchers.containsString("127.0.0.1:2525")))
                    .andExpect(content().string(Matchers.containsString("Voltar ao SMTP do ambiente")));
            assertThat(jdbc.queryForObject("SELECT target FROM audit_event WHERE action = 'SERVER_SMTP_UPDATED'",
                    String.class)).isEqualTo("127.0.0.1:2525");

            mvc.perform(MockMvcRequestBuilders.post("/manager/server/smtp/remove").with(admin()).with(csrf()))
                    .andExpect(flash().attribute("success", "manager.server.smtp.removed"));
            assertThat(notifications.isEmailEnabled()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM smtp_settings", Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'SERVER_SMTP_REMOVED'",
                    Long.class)).isOne();
        } finally {
            cleanSmtp();
        }
    }

    @Test
    void thePasswordIsStoredEncryptedAndNeverShown() throws Exception {
        var password = "senha-do-smtp-que-nao-aparece-na-tela";
        try {
            mvc.perform(MockMvcRequestBuilders.post("/manager/server/smtp").with(admin()).with(csrf())
                            .param("action", "save").param("host", "smtp.msgforge.test").param("port", "587")
                            .param("username", "avisos@msgforge.test").param("password", password)
                            .param("starttls", "true"))
                    .andExpect(flash().attribute("success", "manager.server.smtp.saved"));
            var stored = jdbc.queryForObject("SELECT password_encrypted FROM smtp_settings", String.class);
            assertThat(stored).isNotBlank().doesNotContain(password);
            mvc.perform(MockMvcRequestBuilders.get("/manager/server").with(admin()))
                    .andExpect(content().string(Matchers.not(Matchers.containsString(password))))
                    .andExpect(content().string(Matchers.containsString("guardada; em branco, continua a mesma")));

            // Salvar de novo com a senha em branco mantém a guardada
            mvc.perform(MockMvcRequestBuilders.post("/manager/server/smtp").with(admin()).with(csrf())
                            .param("action", "save").param("host", "smtp.msgforge.test").param("port", "2587")
                            .param("username", "avisos@msgforge.test").param("starttls", "true"))
                    .andExpect(flash().attribute("success", "manager.server.smtp.saved"));
            assertThat(jdbc.queryForObject("SELECT password_encrypted FROM smtp_settings", String.class)).isEqualTo(stored);
        } finally {
            cleanSmtp();
        }
    }

    @Test
    void invalidFieldsAndFailedTestsDoNotSaveAnything() throws Exception {
        try {
            mvc.perform(MockMvcRequestBuilders.post("/manager/server/smtp").with(admin()).with(csrf())
                            .param("action", "save").param("host", "smtp exemplo;com").param("port", "587"))
                    .andExpect(flash().attribute("smtpErrors",
                            java.util.Map.of("host", "manager.server.smtp.error.host.invalid")));
            mvc.perform(MockMvcRequestBuilders.post("/manager/server/smtp").with(admin()).with(csrf())
                            .param("action", "save").param("host", "smtp.msgforge.test")
                            .param("username", "avisos@msgforge.test"))
                    .andExpect(flash().attribute("smtpErrors",
                            java.util.Map.of("password", "manager.server.smtp.error.password.required")));
            // Porta fechada: o teste falha com o motivo e não salva
            var result = mvc.perform(MockMvcRequestBuilders.post("/manager/server/smtp").with(admin()).with(csrf())
                            .param("action", "test").param("host", "127.0.0.1").param("port", "1")
                            .param("timeoutMs", "1000"))
                    .andExpect(redirectedUrl("/manager/server")).andReturn();
            var test = (EmailSender.TestResult) result.getFlashMap().get("smtpTest");
            assertThat(test.ok()).isFalse();
            assertThat(test.reason()).isNotBlank();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM smtp_settings", Long.class)).isZero();
            assertThat(notifications.isEmailEnabled()).isFalse();
        } finally {
            cleanSmtp();
        }
    }

    @Test
    void theTestEmailResultShowsOnTheScreen() throws Exception {
        var to = uniqueName("resultado") + "@exemplo.com";
        try {
            // SMTP numa porta fechada: o e-mail entra na fila e a tela mostra o que aconteceu com ele
            mvc.perform(MockMvcRequestBuilders.post("/manager/server/smtp").with(admin()).with(csrf())
                            .param("action", "save").param("host", "127.0.0.1").param("port", "1")
                            .param("timeoutMs", "1000"))
                    .andExpect(flash().attribute("success", "manager.server.smtp.saved"));
            mvc.perform(MockMvcRequestBuilders.post("/manager/server/test-email").with(admin()).with(csrf())
                            .param("email", to))
                    .andExpect(flash().attribute("sentTo", to));
            mvc.perform(MockMvcRequestBuilders.get("/manager/server/test-emails").with(admin()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(Matchers.containsString(to)))
                    .andExpect(content().string(Matchers.containsString("Últimos e-mails de teste")));
            mvc.perform(MockMvcRequestBuilders.get("/manager/server").with(admin()))
                    .andExpect(content().string(Matchers.containsString("Testar login (não envia e-mail)")))
                    .andExpect(content().string(Matchers.containsString(to)));
        } finally {
            jdbc.update("DELETE FROM outbox WHERE order_key = ?", to);
            cleanSmtp();
        }
    }

    /** Os contextos de teste dividem o banco: SMTP deixado aqui faria os outros pegarem linhas EMAIL. */
    private void cleanSmtp() {
        jdbc.update("DELETE FROM smtp_settings");
        AccessFixtures.reset(jdbc);
        reloadEmail.reload();
    }

    @Autowired
    private EmailSender reloadEmail;

    /** Admin da plataforma (a chave global): a tela Servidor é só dele. */
    private static RequestPostProcessor admin() {
        return authentication(new UsernamePasswordAuthenticationToken(ManagerPrincipal.GLOBAL_KEY, null,
                List.of(new SimpleGrantedAuthority("ROLE_MANAGER"))));
    }
}

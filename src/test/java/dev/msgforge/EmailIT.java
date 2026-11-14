package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import dev.msgforge.notification.Notification;
import dev.msgforge.notification.NotificationService;
import dev.msgforge.support.IntegrationTest;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Notificações por e-mail (ADR 0011) de ponta a ponta: outbox, trabalhador e um SMTP em memória
 * (GreenMail), sem rede. Os outros contextos de teste não têm SMTP, então os trabalhadores deles
 * deixam as linhas EMAIL para o deste contexto.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "msgforge.smtp.username=" + EmailIT.SMTP_USER,
        "msgforge.smtp.password=" + EmailIT.SMTP_PASSWORD,
        "msgforge.smtp.from=Msgforge <avisos@msgforge.test>",
        "msgforge.smtp.starttls=false",
        "msgforge.smtp.timeout-ms=2000",
})
class EmailIT extends IntegrationTest {

    static final String SMTP_USER = "msgforge";
    static final String SMTP_PASSWORD = "segredo-do-smtp-que-nao-vai-para-a-outbox";

    private static final int SMTP_PORT = freePort();
    private static GreenMail smtp = startSmtp();

    @Autowired
    private NotificationService notifications;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void smtpServer(DynamicPropertyRegistry registry) {
        registry.add("msgforge.smtp.host", () -> "127.0.0.1");
        registry.add("msgforge.smtp.port", () -> SMTP_PORT);
    }

    @AfterAll
    static void stopSmtp() {
        smtp.stop();
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static GreenMail startSmtp() {
        var server = new GreenMail(new ServerSetup(SMTP_PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP))
                .withConfiguration(GreenMailConfiguration.aConfig().withUser(SMTP_USER, SMTP_PASSWORD));
        server.start();
        return server;
    }

    private static String recipient() {
        return "teste-" + UUID.randomUUID().toString().substring(0, 8) + "@exemplo.com";
    }

    @Test
    void notificationGoesThroughTheOutboxAndArrivesWithHtmlAndText() throws Exception {
        assertThat(notifications.isEmailEnabled()).isTrue();
        var to = recipient();

        assertThat(notifications.send(Notification.test(to))).isEqualTo(NotificationService.Result.QUEUED);

        var message = awaitMessageFor(to);
        assertThat(message.getSubject()).isEqualTo("E-mail de teste do Msgforge");
        assertThat(message.getFrom()[0].toString()).contains("avisos@msgforge.test");
        var parts = parts(message);
        assertThat(parts.get("text/plain")).contains("O e-mail do Msgforge está funcionando");
        assertThat(parts.get("text/html")).contains("<h1", "O e-mail do Msgforge está funcionando");

        var row = awaitState(to, "FEITO");
        assertThat(row.get("type")).isEqualTo("EMAIL");
        assertThat(row.get("event")).isEqualTo("email.test");
        assertThat(row.get("destination")).isEqualTo(to);
        // O conteúdo do e-mail fica na outbox; a credencial do SMTP, não
        assertThat((String) row.get("payload")).contains("E-mail de teste do Msgforge").doesNotContain(SMTP_PASSWORD);
    }

    @Test
    void smtpDownIsRetriedAndThenDead() throws Exception {
        var to = recipient();
        smtp.stop();
        try {
            notifications.send(Notification.test(to));

            var row = awaitState(to, "MORTO");
            assertThat(row.get("attempts")).isEqualTo(3);   // WEBHOOK_RETRY_MAX_ATTEMPTS dos testes
            assertThat((String) row.get("last_error")).isNotBlank();
        } finally {
            smtp = startSmtp();
        }
        assertThat(messagesFor(to)).isEmpty();
    }

    @Test
    void managerShowsTheSmtpAndSendsATestEmail() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/manager/server").with(user("manager").roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(content().string(Matchers.containsString("127.0.0.1:" + SMTP_PORT)))
                .andExpect(content().string(Matchers.containsString("Ligado")))
                .andExpect(content().string(Matchers.containsString("avisos@msgforge.test")))
                .andExpect(content().string(Matchers.not(Matchers.containsString(SMTP_PASSWORD))));

        var to = recipient();
        mvc.perform(MockMvcRequestBuilders.post("/manager/server/test-email").with(user("manager").roles("MANAGER"))
                        .with(csrf()).param("email", to))
                .andExpect(redirectedUrl("/manager/server"))
                .andExpect(flash().attribute("sentTo", to));

        assertThat(awaitMessageFor(to).getSubject()).isEqualTo("E-mail de teste do Msgforge");
    }

    @Test
    void managerRejectsAnInvalidAddressWithoutQueueing() throws Exception {
        mvc.perform(MockMvcRequestBuilders.post("/manager/server/test-email").with(user("manager").roles("MANAGER"))
                        .with(csrf()).param("email", "ana@exemplo.com\r\nBcc: bia@exemplo.com"))
                .andExpect(redirectedUrl("/manager/server"))
                .andExpect(flash().attribute("fieldError", "manager.server.test.invalid"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE type = 'EMAIL' AND order_key LIKE 'ana@%'",
                Long.class)).isZero();
    }

    private List<MimeMessage> messagesFor(String to) {
        return Arrays.stream(smtp.getReceivedMessages()).filter(m -> {
            try {
                return Arrays.stream(m.getRecipients(Message.RecipientType.TO)).anyMatch(a -> a.toString().equals(to));
            } catch (Exception e) {
                return false;
            }
        }).toList();
    }

    private MimeMessage awaitMessageFor(String to) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            var found = messagesFor(to);
            if (!found.isEmpty()) {
                return found.getFirst();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("e-mail para " + to + " não chegou");
    }

    private Map<String, Object> awaitState(String to, String state) throws InterruptedException {
        var deadline = Instant.now().plus(Duration.ofSeconds(10));
        Map<String, Object> row = null;
        while (Instant.now().isBefore(deadline)) {
            var rows = jdbc.queryForList("SELECT type, event, destination, state, attempts, last_error, "
                    + "payload::text AS payload FROM outbox WHERE order_key = ?", to);
            if (!rows.isEmpty()) {
                row = rows.getFirst();
                if (state.equals(row.get("state"))) {
                    return row;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("linha de " + to + " não chegou a " + state + ": " + row);
    }

    /** Partes da mensagem por tipo (text/plain, text/html), já decodificadas. */
    private static Map<String, String> parts(Part part) throws Exception {
        var found = new HashMap<String, String>();
        collect(part, found);
        return found;
    }

    private static void collect(Part part, Map<String, String> found) throws Exception {
        if (part.isMimeType("multipart/*")) {
            var multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                collect(multipart.getBodyPart(i), found);
            }
        } else if (part.isMimeType("text/plain")) {
            found.put("text/plain", (String) part.getContent());
        } else if (part.isMimeType("text/html")) {
            found.put("text/html", (String) part.getContent());
        }
    }
}

package dev.msgforge.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import dev.msgforge.config.HttpClientConfig;
import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.event.WebhookSender;
import dev.msgforge.event.outbox.Outbox;
import dev.msgforge.event.outbox.OutboxProperties;
import dev.msgforge.security.PrivateNetworkGuard;
import java.net.ServerSocket;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** OUTBOX_ENABLED=false: o e-mail sai pela fila em memória, com o mesmo retry. */
class EmailChannelTest {

    private final MsgforgeProperties properties = new MsgforgeProperties("http://localhost", null,
            new MsgforgeProperties.Webhook(null, false, 2000, new MsgforgeProperties.Retry(5, 200, 1000), 1000, false,
                    List.of()),
            null, "stub", null);
    private final WebhookSender retry = new WebhookSender(
            RestClient.builder().requestFactory(HttpClientConfig.requestFactory(2000)).build(),
            JsonMapper.builder().build(), properties, new PrivateNetworkGuard(false));
    private final Outbox outbox = mock(Outbox.class);
    private GreenMail smtp;

    @AfterEach
    void stop() {
        if (smtp != null) {
            smtp.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private EmailChannel channel(int port) {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        var sender = new EmailSender(new SmtpProperties("127.0.0.1", port, null, null, "avisos@msgforge.test",
                false, 2000));
        return new EmailChannel(sender, new EmailRenderer(messages), outbox, new OutboxProperties(false, 24, 1000, 4, 60),
                mock(ObjectProvider.class), retry, JsonMapper.builder().build(), properties);
    }

    private static int freePort() throws Exception {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void withoutOutboxTheEmailIsRetriedInMemoryUntilTheSmtpAnswers() throws Exception {
        var port = freePort();
        var channel = channel(port);

        channel.submit(Notification.test("ana@exemplo.com"));   // SMTP ainda fora do ar: 1ª tentativa falha
        smtp = new GreenMail(new ServerSetup(port, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.start();

        assertThat(smtp.waitForIncomingEmail(5000, 1)).isTrue();
        assertThat(smtp.getReceivedMessages()[0].getSubject()).isEqualTo("E-mail de teste do Msgforge");
        verifyNoInteractions(outbox);
    }
}

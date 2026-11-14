package dev.msgforge.event.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import dev.msgforge.config.HttpClientConfig;
import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.event.WebhookSender;
import dev.msgforge.event.outbox.Outbox.Entry;
import dev.msgforge.event.outbox.Outbox.Type;
import dev.msgforge.security.PrivateNetworkGuard;
import dev.msgforge.support.WebhookReceiver;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/** Uma tentativa por linha: o resultado vira FEITO, retry agendado ou MORTO. */
class OutboxWorkerTest {

    private static final String PAYLOAD = "{\"event\":\"send.message\",\"instance\":\"loja\",\"data\":{\"z\":1,\"a\":2}}";

    private final WebhookReceiver receiver = new WebhookReceiver();
    private final Outbox outbox = mock(Outbox.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final MsgforgeProperties properties = new MsgforgeProperties("http://localhost", null,
            new MsgforgeProperties.Webhook(null, false, 2000, new MsgforgeProperties.Retry(3, 10, 40), 1000, false, java.util.List.of()),
            null, "stub", null);
    private final WebhookSender sender = new WebhookSender(
            RestClient.builder().requestFactory(HttpClientConfig.requestFactory(2000)).build(),
            JsonMapper.builder().build(), properties, new PrivateNetworkGuard(false));
    private final OutboxWorker worker = new OutboxWorker(outbox, sender, rabbit,
            new OutboxProperties(true, 24, 1000, 4, 60), properties);

    @AfterEach
    void stop() {
        receiver.close();
    }

    private Entry webhook(int attempts, boolean checkPrivateNetwork) {
        return new Entry(7, Type.WEBHOOK, "loja", "send.message", receiver.url("/h"),
                Map.of("x-token", "abc"), checkPrivateNetwork, PAYLOAD, attempts);
    }

    @Test
    void deliveredEntryIsDoneAndTheBodyIsTheStoredPayload() {
        worker.deliver(webhook(1, false));

        verify(outbox).markDone(7);
        var received = receiver.received().getFirst();
        assertThat(received.body()).isEqualTo(PAYLOAD);   // byte a byte, ordem das chaves inclusive
        assertThat(received.header("x-token")).isEqualTo("abc");
        assertThat(received.header("Content-Length")).isNotNull();
    }

    @Test
    void retryableFailureIsRescheduledWithExponentialDelay() {
        receiver.respondWith(path -> 503);

        worker.deliver(webhook(1, false));
        worker.deliver(webhook(2, false));

        verify(outbox).markRetry(7, "HTTP 503", Duration.ofMillis(10));
        verify(outbox).markRetry(7, "HTTP 503", Duration.ofMillis(20));
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void lastAttemptFailingMarksTheEntryDead() {
        receiver.respondWith(path -> 503);

        worker.deliver(webhook(3, false));

        verify(outbox).markDead(7, "HTTP 503");
        verifyNoMoreInteractions(outbox);
    }

    @Test
    void clientErrorIsDeadWithoutRetry() {
        receiver.respondWith(path -> 404);

        worker.deliver(webhook(1, false));

        verify(outbox).markDead(7, "HTTP 404");
        assertThat(receiver.received()).hasSize(1);
    }

    @Test
    void privateNetworkCheckComesFromTheEntryNotFromThisNode() {
        worker.deliver(webhook(1, true));   // este nó tem a proteção desligada; a linha pede a checagem

        verify(outbox).markDead(eq(7L), org.mockito.ArgumentMatchers.startsWith("blocked"));
        assertThat(receiver.received()).isEmpty();
    }

    @Test
    void rabbitEntryIsPublishedWithTheInstanceRoutingKey() {
        worker.deliver(new Entry(9, Type.RABBITMQ, "loja", "send.message", "msgforge_exchange", Map.of(), false,
                PAYLOAD, 1));

        var message = ArgumentCaptor.forClass(Message.class);
        verify(rabbit).send(eq("msgforge_exchange"), eq("loja.send.message"), message.capture());
        assertThat(new String(message.getValue().getBody(), StandardCharsets.UTF_8)).isEqualTo(PAYLOAD);
        assertThat(message.getValue().getMessageProperties().getContentType()).isEqualTo("application/json");
        verify(outbox).markDone(9);
    }

    @Test
    void rabbitFailureIsRetried() {
        doThrow(new AmqpConnectException(new java.net.ConnectException("fora do ar")))
                .when(rabbit).send(anyString(), anyString(), any(Message.class));

        worker.deliver(new Entry(9, Type.RABBITMQ, "loja", "send.message", "msgforge_exchange", Map.of(), false,
                PAYLOAD, 1));

        verify(outbox).markRetry(eq(9L), anyString(), eq(Duration.ofMillis(10)));
    }
}

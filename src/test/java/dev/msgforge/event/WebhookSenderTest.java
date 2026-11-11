package dev.msgforge.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.config.HttpClientConfig;
import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.support.WebhookReceiver;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class WebhookSenderTest {

    private final WebhookReceiver receiver = new WebhookReceiver();
    private final WebhookSender sender = new WebhookSender(
            RestClient.builder().requestFactory(HttpClientConfig.requestFactory(2000)).build(),
            JsonMapper.builder().build(),
            new MsgforgeProperties("http://localhost", null,
                    new MsgforgeProperties.Webhook(null, false, 2000, new MsgforgeProperties.Retry(3, 10, 40)),
                    null, "stub"));

    @AfterEach
    void stop() {
        receiver.close();
    }

    @Test
    void retriesServerErrorsUntilSuccess() {
        var calls = new AtomicInteger();
        receiver.respondWith(path -> calls.incrementAndGet() < 3 ? 500 : 200);

        assertThat(sender.send(receiver.url("/h"), "send.message", Map.of("a", 1), Map.of())).isTrue();
        assertThat(receiver.received()).hasSize(3);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        receiver.respondWith(path -> 503);

        assertThat(sender.send(receiver.url("/h"), "send.message", Map.of(), Map.of())).isFalse();
        assertThat(receiver.received()).hasSize(3);
    }

    @Test
    void doesNotRetryClientErrors() {
        receiver.respondWith(path -> 404);

        assertThat(sender.send(receiver.url("/h"), "send.message", Map.of(), Map.of())).isFalse();
        assertThat(receiver.received()).hasSize(1);
    }

    @Test
    void connectionErrorsFailWithoutThrowing() {
        var url = receiver.url("/h");
        receiver.close();   // nada escutando na porta

        assertThat(sender.send(url, "send.message", Map.of(), Map.of())).isFalse();
    }

    @Test
    void sendsJsonWithContentLengthAndCustomHeaders() {
        assertThat(sender.send(receiver.url("/h"), "send.message", Map.of("event", "send.message"),
                Map.of("X-Token", "abc"))).isTrue();

        var request = receiver.received().getFirst();
        assertThat(request.header("Content-Length")).isEqualTo(String.valueOf(request.body().length()));
        assertThat(request.header("Transfer-Encoding")).isNull();
        assertThat(request.header("Content-Type")).startsWith("application/json");
        assertThat(request.header("X-Token")).isEqualTo("abc");
        assertThat(request.body()).isEqualTo("{\"event\":\"send.message\"}");
    }
}

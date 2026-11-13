package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.event.outbox.OutboxWorker;
import dev.msgforge.support.IntegrationTest;
import dev.msgforge.support.WebhookReceiver.Received;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/** OUTBOX_ENABLED=false: recuo para a fila em memória (ADR 0006), com a mesma ordem e o mesmo corpo. */
@TestPropertySource(properties = "msgforge.outbox.enabled=false")
class OutboxDisabledIT extends IntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void deliversInOrderFromMemoryWithoutTouchingTheOutbox() {
        assertThat(context.getBeanProvider(OutboxWorker.class).getIfAvailable()).isNull();

        var name = uniqueName("memoria");
        var qrFailures = new AtomicInteger();
        webhooks.respondWith(path -> path.equals("/" + name + "/qrcode-updated")
                && qrFailures.incrementAndGet() <= 2 ? 503 : 200);

        post("/instance/create", """
                {"instanceName":"%s","qrcode":true,"webhook":{"url":"%s","byEvents":true}}
                """.formatted(name, webhooks.url("/" + name)));

        webhooks.await(r -> webhooks.received().stream().filter(x -> x.path().startsWith("/" + name + "/")).count() >= 4,
                Duration.ofSeconds(5));
        assertThat(webhooks.received().stream().filter(r -> r.path().startsWith("/" + name + "/")))
                .extracting(Received::path).containsExactly(
                        "/" + name + "/qrcode-updated", "/" + name + "/qrcode-updated",
                        "/" + name + "/qrcode-updated", "/" + name + "/connection-update");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE order_key = ?", Long.class, name))
                .isZero();
    }
}

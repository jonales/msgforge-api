package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.event.GatewayEvent.ApplicationStartup;
import dev.msgforge.event.GatewayEvent.CallReceived;
import dev.msgforge.event.GatewayEvent.PresenceUpdated;
import dev.msgforge.support.IntegrationTest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Webhook global com filtro de eventos (WEBHOOK_GLOBAL_EVENTS) e o APPLICATION_STARTUP, que não
 * pertence a nenhuma instância. Contexto próprio: o webhook global recebe tudo o que o contexto emite.
 */
@TestPropertySource(properties = {
        "msgforge.webhook.global-enabled=true",
        "msgforge.webhook.global-events=APPLICATION_STARTUP,call",
})
class GlobalEventsIT extends IntegrationTest {

    private static final String GLOBAL_PATH = "/global-" + java.util.UUID.randomUUID();
    private static final String ANA = "5561911111111@s.whatsapp.net";

    @DynamicPropertySource
    static void globalWebhook(DynamicPropertyRegistry registry) {
        registry.add("msgforge.webhook.global-url", () -> webhooks.url(GLOBAL_PATH));
    }

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void startupIsAnnouncedToTheGlobalWebhookWithoutAnInstance() {
        // O boot deste contexto já gravou o seu na outbox (a chave das entregas sem instância é "")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE type = 'WEBHOOK_GLOBAL' AND order_key = '' "
                + "AND event = 'application.startup' AND destination = ?", Long.class, webhooks.url(GLOBAL_PATH)))
                .isGreaterThanOrEqualTo(1L);

        webhooks.clear();
        events.publishEvent(new ApplicationStartup());
        var received = webhooks.await(r -> r.path().equals(GLOBAL_PATH) && r.body().contains("application.startup"),
                Duration.ofSeconds(5));
        var envelope = json.readTree(received.body());
        assertThat(envelope.get("event").asString()).isEqualTo("application.startup");
        assertThat(envelope.get("instance").isNull()).isTrue();
        assertThat(envelope.get("data").get("status").asString()).isEqualTo("started");
    }

    @Test
    void theGlobalWebhookOnlyGetsTheConfiguredEvents() {
        var name = uniqueName("global");
        assertThat(post("/instance/create", """
                {"instanceName":"%s"}""".formatted(name)).status()).isEqualTo(201);

        events.publishEvent(new PresenceUpdated(name, ANA, ANA, "composing", null));
        events.publishEvent(new CallReceived(name, "CALL3", ANA, ANA, null, false, "offer", false, 0));

        var received = webhooks.await(r -> r.path().equals(GLOBAL_PATH) && r.body().contains("CALL3"),
                Duration.ofSeconds(5));
        assertThat(json.readTree(received.body()).get("instance").asString()).isEqualTo(name);
        // Nem a presença nem o connection/instance de criar a instância foram gravados
        assertThat(jdbc.queryForList("SELECT event FROM outbox WHERE order_key = ?", String.class, name))
                .containsExactly("call");
    }
}

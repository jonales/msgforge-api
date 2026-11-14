package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.event.GatewayEvent;
import dev.msgforge.event.GatewayEvent.CallReceived;
import dev.msgforge.event.GatewayEvent.ChatsDeleted;
import dev.msgforge.event.GatewayEvent.ChatsUpdated;
import dev.msgforge.event.GatewayEvent.ChatsUpserted;
import dev.msgforge.event.GatewayEvent.ContactsUpdated;
import dev.msgforge.event.GatewayEvent.ContactsUpserted;
import dev.msgforge.event.GatewayEvent.MessageDeleted;
import dev.msgforge.event.GatewayEvent.MessagesSynced;
import dev.msgforge.event.GatewayEvent.PresenceUpdated;
import dev.msgforge.support.IntegrationTest;
import dev.msgforge.support.WebhookReceiver.Received;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Eventos do contrato além de conexão e mensagens: o webhook da instância recebe cada um no
 * envelope do contrato. Os de grupo saem das operações do stub; os demais só o motor real gera,
 * então o teste os publica como o gateway publicaria.
 */
class ContractEventsIT extends IntegrationTest {

    private static final String ANA = "5561911111111@s.whatsapp.net";

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    private String createInstance(String name, String webhook) {
        assertThat(post("/instance/create", """
                {"instanceName":"%s","webhook":%s}""".formatted(name, webhook)).status()).isEqualTo(201);
        return jdbc.queryForObject("SELECT id::text FROM instance WHERE name = ?", String.class, name);
    }

    private JsonNode body(Received received) {
        return json.readTree(received.body());
    }

    private JsonNode awaitEvent(String name, String path, String event) {
        var received = webhooks.await(r -> r.path().equals(path) && body(r).get("event").asString().equals(event)
                && body(r).get("instance").asString().equals(name), Duration.ofSeconds(5));
        var envelope = body(received);
        assertThat(envelope.has("date_time")).isTrue();
        assertThat(envelope.has("server_url")).isTrue();
        assertThat(envelope.get("apikey").asString()).isNotBlank();
        assertThat(envelope.get("destination").asString()).endsWith(path);
        return envelope.get("data");
    }

    @Test
    void groupOperationsOfTheStubEmitTheGroupEvents() {
        var name = uniqueName("grupo-eventos");
        createInstance(name, """
                {"url":"%s","byEvents":true,"events":["GROUPS_UPSERT","GROUP_UPDATE","GROUP_PARTICIPANTS_UPDATE"]}"""
                .formatted(webhooks.url("/" + name)));

        var created = post("/group/create/" + name, """
                {"subject":"Família","participants":["%s"]}""".formatted(ANA));
        assertThat(created.status()).isEqualTo(201);
        var groupJid = created.body().get("id").asString();

        var upsert = awaitEvent(name, "/" + name + "/groups-upsert", "groups.upsert");
        assertThat(upsert.get(0).get("id").asString()).isEqualTo(groupJid);
        assertThat(upsert.get(0).get("subject").asString()).isEqualTo("Família");
        assertThat(upsert.get(0).get("size").asInt()).isEqualTo(2);
        assertThat(upsert.get(0).get("participants").get(0).get("admin").asString()).isEqualTo("superadmin");

        assertThat(post("/group/updateGroupSubject/" + name + "?groupJid=" + groupJid, """
                {"subject":"Família Silva"}""").status()).isEqualTo(201);
        var update = awaitEvent(name, "/" + name + "/groups-update", "groups.update");
        assertThat(update.get(0).get("id").asString()).isEqualTo(groupJid);
        assertThat(update.get(0).get("subject").asString()).isEqualTo("Família Silva");

        assertThat(post("/group/updateParticipant/" + name + "?groupJid=" + groupJid, """
                {"action":"promote","participants":["%s"]}""".formatted(ANA)).status()).isEqualTo(201);
        var participants = awaitEvent(name, "/" + name + "/group-participants-update", "group-participants.update");
        assertThat(participants.get("id").asString()).isEqualTo(groupJid);
        assertThat(participants.get("action").asString()).isEqualTo("promote");
        assertThat(participants.get("participants").get(0).asString()).isEqualTo(ANA);
        assertThat(participants.get("participantsData").get(0).get("phoneNumber").asString()).isEqualTo("5561911111111");
    }

    @Test
    void engineEventsReachTheWebhookInTheContractFormat() {
        var name = uniqueName("motor-eventos");
        var instanceId = createInstance(name, """
                {"url":"%s"}""".formatted(webhooks.url("/" + name)));
        var path = "/" + name;

        events.publishEvent(new MessagesSynced(name, List.of(Map.of("key", Map.of("id", "HIST1")))));
        assertThat(awaitEvent(name, path, "messages.set").get(0).get("key").get("id").asString()).isEqualTo("HIST1");

        events.publishEvent(new MessageDeleted(name, ANA, false, "3EB0AA", null));
        var deleted = awaitEvent(name, path, "messages.delete");
        assertThat(deleted.get("id").asString()).isEqualTo("3EB0AA");
        assertThat(deleted.get("status").asString()).isEqualTo("DELETED");

        events.publishEvent(new ContactsUpserted(name, List.of(new GatewayEvent.Contact(ANA, "Ana"))));
        var contacts = awaitEvent(name, path, "contacts.upsert");
        assertThat(contacts.get(0).get("remoteJid").asString()).isEqualTo(ANA);
        assertThat(contacts.get(0).get("instanceId").asString()).isEqualTo(instanceId);

        events.publishEvent(new ContactsUpdated(name, List.of(new GatewayEvent.Contact(ANA, "Ana Maria"))));
        assertThat(awaitEvent(name, path, "contacts.update").get(0).get("pushName").asString()).isEqualTo("Ana Maria");

        events.publishEvent(new PresenceUpdated(name, ANA, ANA, "composing", null));
        assertThat(awaitEvent(name, path, "presence.update").get("presences").get(ANA).get("lastKnownPresence")
                .asString()).isEqualTo("composing");

        events.publishEvent(new ChatsUpserted(name, List.of(new GatewayEvent.Chat(ANA, "Ana", 3))));
        assertThat(awaitEvent(name, path, "chats.upsert").get(0).get("unreadMessages").asInt()).isEqualTo(3);

        events.publishEvent(new ChatsUpdated(name, List.of(ANA)));
        assertThat(awaitEvent(name, path, "chats.update").get(0).get("instanceId").asString()).isEqualTo(instanceId);

        events.publishEvent(new ChatsDeleted(name, List.of(ANA)));
        assertThat(awaitEvent(name, path, "chats.delete").get(0).asString()).isEqualTo(ANA);

        events.publishEvent(new CallReceived(name, "CALL1", ANA, ANA, null, false, "offer", false, 1_700_000_000_000L));
        var call = awaitEvent(name, path, "call");
        assertThat(call.get("id").asString()).isEqualTo("CALL1");
        assertThat(call.get("status").asString()).isEqualTo("offer");
    }

    @Test
    void eventsNobodySubscribesAreNotWrittenToTheOutbox() {
        var name = uniqueName("filtro");
        createInstance(name, """
                {"url":"%s","events":["CALL"]}""".formatted(webhooks.url("/" + name)));

        events.publishEvent(new PresenceUpdated(name, ANA, ANA, "composing", null));
        events.publishEvent(new ContactsUpserted(name, List.of(new GatewayEvent.Contact(ANA, "Ana"))));
        events.publishEvent(new CallReceived(name, "CALL2", ANA, ANA, null, false, "offer", false, 0));

        awaitEvent(name, "/" + name, "call");
        // Sem transação, a outbox já foi gravada quando publishEvent voltou
        assertThat(jdbc.queryForList("SELECT event FROM outbox WHERE order_key = ?", String.class, name))
                .containsExactly("call");
    }
}

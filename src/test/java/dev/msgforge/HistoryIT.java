package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.event.GatewayEvent.MessageReceived;
import dev.msgforge.event.GatewayEvent.MessageStatusUpdated;
import dev.msgforge.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/** Histórico (ADR 0008): envios e eventos do motor gravados no banco, lidos por /chat/find*. */
class HistoryIT extends IntegrationTest {

    private static final String ANA = "5561911111111@s.whatsapp.net";
    private static final String BIA = "5561922222222@s.whatsapp.net";

    @Autowired
    private ApplicationEventPublisher events;

    private String name;

    @BeforeEach
    void createInstance() {
        name = uniqueName("historico");
        assertThat(post("/instance/create", """
                {"instanceName":"%s"}""".formatted(name)).status()).isEqualTo(201);
    }

    /** Como o motor real publica: formato do MESSAGES_UPSERT do contrato. */
    private void receive(String from, String id, String text, long timestamp) {
        events.publishEvent(new MessageReceived(name, Map.of(
                "key", Map.of("remoteJid", from, "fromMe", false, "id", id),
                "pushName", "Contato " + id,
                "status", "DELIVERY_ACK",
                "message", Map.of("conversation", text),
                "messageType", "conversation",
                "messageTimestamp", timestamp)));
    }

    private Response find(String json) {
        return post("/chat/findMessages/" + name, json);
    }

    @Test
    void sentAndReceivedMessagesAreListedNewestFirst() {
        receive(ANA, "IN-1", "oi", 1_700_000_000L);
        receive(ANA, "IN-1", "oi (reentregue)", 1_700_000_000L);   // repetida: ignorada
        var sent = post("/message/sendText/" + name, """
                {"number":"5561911111111","text":"olá, Ana"}""");
        assertThat(sent.status()).isEqualTo(201);
        var sentId = sent.body().get("key").get("id").asString();
        receive(BIA, "IN-2", "bom dia", 1_600_000_000L);

        var all = find("{}");
        assertThat(all.status()).isEqualTo(200);
        var page = all.body().get("messages");
        assertThat(page.get("total").asLong()).isEqualTo(3);
        assertThat(page.get("pages").asLong()).isEqualTo(1);
        assertThat(page.get("currentPage").asInt()).isEqualTo(1);
        var records = page.get("records");
        assertThat(records.get(0).get("key").get("id").asString()).isEqualTo(sentId);   // a mais nova
        assertThat(records.get(0).get("key").get("fromMe").asBoolean()).isTrue();
        assertThat(records.get(0).get("message").get("conversation").asString()).isEqualTo("olá, Ana");
        assertThat(records.get(1).get("message").get("conversation").asString()).isEqualTo("oi");
        assertThat(records.get(1).get("pushName").asString()).isEqualTo("Contato IN-1");
        assertThat(records.get(2).get("key").get("remoteJid").asString()).isEqualTo(BIA);

        // Filtros da key (o número é normalizado) e paginação
        var fromAna = find("""
                {"where":{"key":{"remoteJid":"5561911111111"}}}""").body().get("messages");
        assertThat(fromAna.get("total").asLong()).isEqualTo(2);
        var received = find("""
                {"where":{"key":{"fromMe":false}},"page":2,"offset":1}""").body().get("messages");
        assertThat(received.get("total").asLong()).isEqualTo(2);
        assertThat(received.get("pages").asLong()).isEqualTo(2);
        assertThat(received.get("records").size()).isEqualTo(1);
        assertThat(received.get("records").get(0).get("key").get("id").asString()).isEqualTo("IN-2");

        assertThat(find("""
                {"offset":501}""").status()).isEqualTo(400);
    }

    @Test
    void messagesWithoutStatusAreStillRecorded() {
        // Nem todo motor manda o status na mensagem recebida
        events.publishEvent(new MessageReceived(name, Map.of(
                "key", Map.of("remoteJid", ANA, "fromMe", false, "id", "SEM-STATUS"),
                "message", Map.of("conversation", "sem status"),
                "messageType", "conversation",
                "messageTimestamp", 1_700_000_000L)));
        events.publishEvent(new MessageStatusUpdated(name, Map.of(
                "keyId", "SEM-STATUS", "remoteJid", ANA, "fromMe", false)));   // status ausente: ignorado

        var record = find("""
                {"where":{"key":{"id":"SEM-STATUS"}}}""").body().get("messages").get("records").get(0);
        assertThat(record.get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void statusOnlyMovesForward() {
        receive(ANA, "IN-9", "lê aí", 1_700_000_000L);
        for (var status : new String[] {"READ", "DELIVERY_ACK"}) {   // o DELIVERY_ACK chega atrasado
            events.publishEvent(new MessageStatusUpdated(name, Map.of(
                    "keyId", "IN-9", "remoteJid", ANA, "fromMe", false, "status", status)));
        }

        var record = find("""
                {"where":{"key":{"id":"IN-9"}}}""").body().get("messages").get("records").get(0);
        assertThat(record.get("status").asString()).isEqualTo("READ");
    }

    @Test
    void chatsShowLastMessageAndContactName() {
        receive(ANA, "IN-1", "oi", 1_700_000_000L);
        receive(BIA, "IN-2", "bom dia", 1_600_000_000L);
        assertThat(post("/message/sendText/" + name, """
                {"number":"5561911111111","text":"tudo bem?"}""").status()).isEqualTo(201);

        var chats = post("/chat/findChats/" + name, "{}");
        assertThat(chats.status()).isEqualTo(200);
        assertThat(chats.body().size()).isEqualTo(2);
        var ana = chats.body().get(0);
        assertThat(ana.get("remoteJid").asString()).isEqualTo(ANA);
        assertThat(ana.get("pushName").asString()).isEqualTo("Contato IN-1");   // da última recebida
        assertThat(ana.get("lastMessage").get("message").get("conversation").asString()).isEqualTo("tudo bem?");
        assertThat(chats.body().get(1).get("updatedAt").asString()).isEqualTo("2020-09-13T12:26:40Z");
    }

    @Autowired
    private dev.msgforge.history.MessageHistory history;

    @Test
    void retentionDeletesOnlyMessagesOlderThanThePeriod() {
        var now = java.time.Instant.parse("2026-11-12T12:00:00Z");
        receive(ANA, "VELHA-1", "de 2023", 1_700_000_000L);
        receive(ANA, "VELHA-2", "de 2020", 1_600_000_000L);
        receive(ANA, "NOVA", "de hoje", now.minusSeconds(3600).getEpochSecond());
        var clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC);

        var keepForever = new dev.msgforge.history.HistoryRetention(history,
                new dev.msgforge.history.HistoryProperties(true, 0), clock);
        assertThat(keepForever.purge()).isZero();

        var oneDay = new dev.msgforge.history.HistoryRetention(history,
                new dev.msgforge.history.HistoryProperties(true, 1), clock);
        assertThat(oneDay.purge()).isGreaterThanOrEqualTo(2);   // outras classes de IT também têm mensagens antigas

        var left = find("{}").body().get("messages");
        assertThat(left.get("total").asLong()).isEqualTo(1);
        assertThat(left.get("records").get(0).get("key").get("id").asString()).isEqualTo("NOVA");
    }

    @Test
    void historyIsPerInstanceAndGoesAwayWithIt() {
        receive(ANA, "IN-1", "oi", 1_700_000_000L);
        var other = uniqueName("outra");
        post("/instance/create", """
                {"instanceName":"%s"}""".formatted(other));
        assertThat(post("/chat/findMessages/" + other, "{}").body().get("messages").get("total").asLong()).isZero();

        assertThat(delete("/instance/delete/" + name).status()).isEqualTo(200);
        assertThat(post("/chat/findMessages/" + name, "{}").status()).isEqualTo(404);
    }
}

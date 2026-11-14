package dev.msgforge.event;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.event.GatewayEvent.ApplicationStartup;
import dev.msgforge.event.GatewayEvent.CallReceived;
import dev.msgforge.event.GatewayEvent.Chat;
import dev.msgforge.event.GatewayEvent.ChatsDeleted;
import dev.msgforge.event.GatewayEvent.ChatsUpdated;
import dev.msgforge.event.GatewayEvent.ChatsUpserted;
import dev.msgforge.event.GatewayEvent.Contact;
import dev.msgforge.event.GatewayEvent.ContactsUpdated;
import dev.msgforge.event.GatewayEvent.ContactsUpserted;
import dev.msgforge.event.GatewayEvent.GroupParticipantsUpdated;
import dev.msgforge.event.GatewayEvent.GroupUpdated;
import dev.msgforge.event.GatewayEvent.GroupsUpserted;
import dev.msgforge.event.GatewayEvent.MessageDeleted;
import dev.msgforge.event.GatewayEvent.MessagesSynced;
import dev.msgforge.event.GatewayEvent.PresenceUpdated;
import dev.msgforge.gateway.WhatsAppGateway.GroupInfo;
import dev.msgforge.gateway.WhatsAppGateway.GroupParticipant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** O {@code data} de cada evento, serializado como sai no webhook (docs/eventos.md). */
class EventPayloadsTest {

    private static final String ID = "00000000-0000-0000-0000-000000000001";
    private static final String ANA = "5561911111111@s.whatsapp.net";
    private static final String GROUP = "120363000000000001@g.us";
    private final JsonMapper json = JsonMapper.builder().build();

    private String data(GatewayEvent event) {
        return json.writeValueAsString(EventPayloads.dataOf(event, ID));
    }

    @Test
    void applicationStartup() {
        assertThat(data(new ApplicationStartup())).isEqualTo("{\"status\":\"started\"}");
    }

    @Test
    void messagesSetIsTheListOfUpserts() {
        var message = Map.<String, Object>of("key", Map.of("id", "A"));
        assertThat(data(new MessagesSynced("i", List.of(message)))).isEqualTo("[{\"key\":{\"id\":\"A\"}}]");
    }

    @Test
    void messagesDeleteIsTheKeyWithStatusDeleted() {
        assertThat(data(new MessageDeleted("i", ANA, false, "3EB0AA", null)))
                .isEqualTo("{\"remoteJid\":\"" + ANA + "\",\"fromMe\":false,\"id\":\"3EB0AA\",\"status\":\"DELETED\"}");
        assertThat(data(new MessageDeleted("i", GROUP, true, "3EB0BB", ANA)))
                .isEqualTo("{\"remoteJid\":\"" + GROUP + "\",\"fromMe\":true,\"id\":\"3EB0BB\",\"participant\":\""
                        + ANA + "\",\"status\":\"DELETED\"}");
    }

    @Test
    void contactsCarryTheInstanceAndFallBackToTheNumberAsName() {
        assertThat(data(new ContactsUpserted("i", List.of(new Contact(ANA, "Ana"), new Contact(ANA, null)))))
                .isEqualTo("[{\"remoteJid\":\"" + ANA + "\",\"pushName\":\"Ana\",\"profilePicUrl\":null,"
                        + "\"instanceId\":\"" + ID + "\"},{\"remoteJid\":\"" + ANA + "\",\"pushName\":\"5561911111111\","
                        + "\"profilePicUrl\":null,\"instanceId\":\"" + ID + "\"}]");
        assertThat(data(new ContactsUpdated("i", List.of(new Contact(ANA, "Ana Maria")))))
                .contains("\"pushName\":\"Ana Maria\"");
    }

    @Test
    void presenceIsKeyedByParticipant() {
        assertThat(data(new PresenceUpdated("i", ANA, ANA, "composing", null)))
                .isEqualTo("{\"id\":\"" + ANA + "\",\"presences\":{\"" + ANA + "\":{\"lastKnownPresence\":\"composing\"}}}");
        assertThat(data(new PresenceUpdated("i", GROUP, ANA, "unavailable", 1_700_000_000L)))
                .isEqualTo("{\"id\":\"" + GROUP + "\",\"presences\":{\"" + ANA
                        + "\":{\"lastKnownPresence\":\"unavailable\",\"lastSeen\":1700000000}}}");
    }

    @Test
    void chats() {
        assertThat(data(new ChatsUpserted("i", List.of(new Chat(ANA, "Ana", 2), new Chat(GROUP, null, null)))))
                .isEqualTo("[{\"remoteJid\":\"" + ANA + "\",\"instanceId\":\"" + ID + "\",\"name\":\"Ana\","
                        + "\"unreadMessages\":2},{\"remoteJid\":\"" + GROUP + "\",\"instanceId\":\"" + ID
                        + "\",\"unreadMessages\":0}]");
        assertThat(data(new ChatsUpdated("i", List.of(ANA))))
                .isEqualTo("[{\"remoteJid\":\"" + ANA + "\",\"instanceId\":\"" + ID + "\"}]");
        assertThat(data(new ChatsDeleted("i", List.of(ANA)))).isEqualTo("[\"" + ANA + "\"]");
    }

    @Test
    void groupsUpsertIsTheGroupMetadata() {
        var group = new GroupInfo(GROUP, "Família", ANA, 10, 10, ANA, "Regras", false, true,
                List.of(new GroupParticipant(ANA, "superadmin"), new GroupParticipant("5561922222222@s.whatsapp.net", null)));
        assertThat(data(new GroupsUpserted("i", List.of(group)))).isEqualTo("[{\"id\":\"" + GROUP
                + "\",\"subject\":\"Família\",\"subjectOwner\":\"" + ANA + "\",\"subjectTime\":10,\"size\":2,"
                + "\"creation\":10,\"owner\":\"" + ANA + "\",\"desc\":\"Regras\",\"restrict\":true,\"announce\":false,"
                + "\"participants\":[{\"id\":\"" + ANA + "\",\"admin\":\"superadmin\"},"
                + "{\"id\":\"5561922222222@s.whatsapp.net\",\"admin\":null}]}]");
    }

    @Test
    void groupUpdateCarriesOnlyWhatChanged() {
        assertThat(data(new GroupUpdated("i", GROUP, ANA, "Novo nome", null, null, null)))
                .isEqualTo("[{\"id\":\"" + GROUP + "\",\"author\":\"" + ANA + "\",\"subject\":\"Novo nome\"}]");
        assertThat(data(new GroupUpdated("i", GROUP, null, null, null, true, null)))
                .isEqualTo("[{\"id\":\"" + GROUP + "\",\"announce\":true}]");
    }

    @Test
    void groupParticipantsKeepTheJidsAndAddTheNumbers() {
        assertThat(data(new GroupParticipantsUpdated("i", GROUP, ANA, List.of(ANA, "123456789@lid"), "add")))
                .isEqualTo("{\"id\":\"" + GROUP + "\",\"author\":\"" + ANA + "\",\"participants\":[\"" + ANA
                        + "\",\"123456789@lid\"],\"action\":\"add\",\"participantsData\":[{\"jid\":\"" + ANA
                        + "\",\"phoneNumber\":\"5561911111111\"},{\"jid\":\"123456789@lid\"}]}");
    }

    @Test
    void call() {
        assertThat(data(new CallReceived("i", "CALL1", ANA, ANA, null, true, "offer", false, 1_700_000_000_000L)))
                .isEqualTo("{\"id\":\"CALL1\",\"from\":\"" + ANA + "\",\"chatId\":\"" + ANA + "\",\"isGroup\":false,"
                        + "\"isVideo\":true,\"status\":\"offer\",\"offline\":false,\"date\":\"2023-11-14T22:13:20Z\"}");
        assertThat(data(new CallReceived("i", "CALL2", ANA, GROUP, GROUP, false, "terminate", false, 0)))
                .contains("\"isGroup\":true,\"groupJid\":\"" + GROUP + "\"");
    }
}

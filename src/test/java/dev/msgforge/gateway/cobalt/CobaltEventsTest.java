package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.auties00.cobalt.stanza.model.Stanza;
import com.github.auties00.cobalt.stanza.model.StanzaBuilder;
import com.github.auties00.cobalt.wire.core.jid.Jid;
import com.github.auties00.cobalt.wire.core.message.MessageKeyBuilder;
import com.github.auties00.cobalt.wire.linked.call.CallEndReason;
import com.github.auties00.cobalt.wire.linked.call.IncomingCall;
import com.github.auties00.cobalt.wire.linked.chat.ChatMessageInfoBuilder;
import com.github.auties00.cobalt.wire.linked.contact.ContactBuilder;
import com.github.auties00.cobalt.wire.linked.contact.ContactStatus;
import com.github.auties00.cobalt.wire.linked.message.LinkedMessageContainer;
import com.github.auties00.cobalt.wire.linked.message.system.ProtocolMessage;
import com.github.auties00.cobalt.wire.linked.message.system.ProtocolMessageBuilder;
import com.github.auties00.cobalt.wire.linked.sync.action.chat.ArchiveChatActionBuilder;
import com.github.auties00.cobalt.wire.linked.sync.action.chat.DeleteChatActionBuilder;
import com.github.auties00.cobalt.wire.linked.sync.action.contact.ContactActionBuilder;
import dev.msgforge.event.GatewayEvent;
import dev.msgforge.event.GatewayEvent.CallReceived;
import dev.msgforge.event.GatewayEvent.ChatsDeleted;
import dev.msgforge.event.GatewayEvent.ChatsUpdated;
import dev.msgforge.event.GatewayEvent.ContactsUpdated;
import dev.msgforge.event.GatewayEvent.GroupParticipantsUpdated;
import dev.msgforge.event.GatewayEvent.GroupUpdated;
import dev.msgforge.event.GatewayEvent.GroupsUpserted;
import dev.msgforge.event.GatewayEvent.MessageDeleted;
import dev.msgforge.gateway.WhatsAppGateway.GroupParticipant;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Callbacks e nós do Cobalt montados com os builders reais, offline. */
class CobaltEventsTest {

    private static final String ANA = "5561911111111@s.whatsapp.net";
    private static final String GROUP = "120363000000000001@g.us";
    private final LidResolver lids = new LidResolver();

    private static Stanza node(String description, Map<String, String> attributes, Stanza... children) {
        var builder = new StanzaBuilder().description(description);
        attributes.forEach(builder::attribute);
        if (children.length > 0) {
            builder.content(children);
        }
        return builder.build();
    }

    private static Stanza text(String description, String content) {
        return new StanzaBuilder().description(description).content(content).build();
    }

    private static Stanza gp2(Stanza... actions) {
        return node("notification", Map.of("from", GROUP, "type", "w:gp2", "participant", ANA, "id", "1"), actions);
    }

    @Test
    void revokeBecomesMessagesDeleteWithTheRevokedId() {
        var revoked = new MessageKeyBuilder().parentJid(Jid.of(ANA)).fromMe(true).id("ORIGINAL").build();
        var protocol = new ProtocolMessageBuilder().type(ProtocolMessage.Type.REVOKE).key(revoked).build();
        var key = new MessageKeyBuilder().parentJid(Jid.of(ANA)).fromMe(false).id("REVOKE-MSG").build();
        var info = new ChatMessageInfoBuilder().key(key).message(LinkedMessageContainer.of(protocol)).build();

        assertThat(CobaltEvents.revoke("i", info, lids))
                .contains(new MessageDeleted("i", ANA, false, "ORIGINAL", null));
    }

    @Test
    void groupRevokeCarriesTheParticipant() {
        var revoked = new MessageKeyBuilder().parentJid(Jid.of(GROUP)).id("ORIGINAL").build();
        var protocol = new ProtocolMessageBuilder().type(ProtocolMessage.Type.REVOKE).key(revoked).build();
        var key = new MessageKeyBuilder().parentJid(Jid.of(GROUP)).fromMe(false).id("REVOKE-MSG").build();
        var info = new ChatMessageInfoBuilder().key(key).senderJid(Jid.of(ANA))
                .message(LinkedMessageContainer.of(protocol)).build();

        assertThat(CobaltEvents.revoke("i", info, lids))
                .contains(new MessageDeleted("i", GROUP, false, "ORIGINAL", ANA));
    }

    @Test
    void otherMessagesAreNotDeletions() {
        var key = new MessageKeyBuilder().parentJid(Jid.of(ANA)).id("X").build();
        var text = new ChatMessageInfoBuilder().key(key).message(LinkedMessageContainer.of("oi")).build();
        var edit = new ChatMessageInfoBuilder().key(key).message(LinkedMessageContainer.of(
                new ProtocolMessageBuilder().type(ProtocolMessage.Type.MESSAGE_EDIT).build())).build();

        assertThat(CobaltEvents.revoke("i", text, lids)).isEmpty();
        assertThat(CobaltEvents.revoke("i", edit, lids)).isEmpty();
    }

    @Test
    void contactNamePrefersTheAddressBook() {
        var contact = new ContactBuilder().jid(Jid.of(ANA)).fullName("Ana Maria").chosenName("Ana").build();
        assertThat(CobaltEvents.contact(contact, lids)).isEqualTo(new GatewayEvent.Contact(ANA, "Ana"));
        assertThat(CobaltEvents.contact(new ContactBuilder().jid(Jid.of(ANA)).build(), lids))
                .isEqualTo(new GatewayEvent.Contact(ANA, null));
    }

    @Test
    void presenceUsesTheBaileysNames() {
        var contact = new ContactBuilder().jid(Jid.of(ANA)).lastKnownPresence(ContactStatus.COMPOSING).build();
        var event = CobaltEvents.presence("i", Jid.of(GROUP), Jid.of(ANA), contact, lids);
        assertThat(event.chatJid()).isEqualTo(GROUP);
        assertThat(event.participantJid()).isEqualTo(ANA);
        assertThat(event.presence()).isEqualTo("composing");
        assertThat(CobaltEvents.presence(ContactStatus.RECORDING)).isEqualTo("recording");
        assertThat(CobaltEvents.presence(null)).isEqualTo("unavailable");
    }

    @Test
    void appStateActionsBecomeChatAndContactEvents() {
        assertThat(CobaltEvents.appState("i", new ArchiveChatActionBuilder().archived(true).build(),
                "[\"archive\",\"" + ANA + "\"]", lids)).contains(new ChatsUpdated("i", List.of(ANA)));
        assertThat(CobaltEvents.appState("i", new DeleteChatActionBuilder().build(),
                "[\"deleteChat\",\"" + ANA + "\",\"1\"]", lids)).contains(new ChatsDeleted("i", List.of(ANA)));
        assertThat(CobaltEvents.appState("i", new ContactActionBuilder().fullName("Ana Maria").build(),
                "[\"contact\",\"" + ANA + "\"]", lids))
                .contains(new ContactsUpdated("i", List.of(new GatewayEvent.Contact(ANA, "Ana Maria"))));
    }

    @Test
    void appStateWithoutAJidIsIgnored() {
        var archive = new ArchiveChatActionBuilder().archived(true).build();
        assertThat(CobaltEvents.appState("i", archive, "[\"archive\"]", lids)).isEmpty();
        assertThat(CobaltEvents.appState("i", archive, "não é json", lids)).isEmpty();
        assertThat(CobaltEvents.appState("i", archive, null, lids)).isEmpty();
    }

    @Test
    void callOfferAndEnd() {
        var call = new IncomingCall("CALL1", Jid.of(ANA), Jid.of(ANA), Instant.ofEpochMilli(1_700_000_000_000L),
                true, false, null, false);
        var offer = CobaltEvents.offer("i", call, lids);
        assertThat(offer).isEqualTo(new CallReceived("i", "CALL1", ANA, ANA, null, true, "offer", false,
                1_700_000_000_000L));

        var rejected = CobaltEvents.ended("i", "CALL1", Jid.of(ANA), CallEndReason.REJECTED, offer, lids);
        assertThat(rejected.status()).isEqualTo("reject");
        assertThat(rejected.isVideo()).isTrue();
        assertThat(CobaltEvents.ended("i", "CALL1", Jid.of(ANA), CallEndReason.TIMEOUT, null, lids).status())
                .isEqualTo("timeout");
        assertThat(CobaltEvents.ended("i", "CALL1", Jid.of(ANA), CallEndReason.HANGUP, null, lids).status())
                .isEqualTo("terminate");
    }

    @Test
    void groupParticipantsNotification() {
        var events = CobaltEvents.groupNotification("i", gp2(
                node("add", Map.of(), node("participant", Map.of("jid", "5561922222222@s.whatsapp.net"))),
                node("promote", Map.of(), node("participant", Map.of("jid", ANA)))), lids);

        assertThat(events).containsExactly(
                new GroupParticipantsUpdated("i", GROUP, ANA, List.of("5561922222222@s.whatsapp.net"), "add"),
                new GroupParticipantsUpdated("i", GROUP, ANA, List.of(ANA), "promote"));
    }

    @Test
    void lidParticipantsAreTranslatedWithThePhoneNumberOfTheNode() {
        var events = CobaltEvents.groupNotification("i", gp2(node("remove", Map.of(),
                node("participant", Map.of("jid", "123456789@lid", "phone_number", ANA)),
                node("participant", Map.of("jid", "987654321@lid")))), lids);

        assertThat(events).containsExactly(
                new GroupParticipantsUpdated("i", GROUP, ANA, List.of(ANA, "987654321@lid"), "remove"));
    }

    @Test
    void groupSettingsNotification() {
        var events = CobaltEvents.groupNotification("i", gp2(
                node("subject", Map.of("subject", "Novo nome", "s_t", "10")),
                node("description", Map.of("id", "D1"), text("body", "Regras novas")),
                node("description", Map.of("id", "D2"), node("delete", Map.of())),
                node("announcement", Map.of()),
                node("unlocked", Map.of()),
                node("ephemeral", Map.of("expiration", "86400"))), lids);

        assertThat(events).containsExactly(
                new GroupUpdated("i", GROUP, ANA, "Novo nome", null, null, null),
                new GroupUpdated("i", GROUP, ANA, null, "Regras novas", null, null),
                new GroupUpdated("i", GROUP, ANA, null, "", null, null),
                new GroupUpdated("i", GROUP, ANA, null, null, true, null),
                new GroupUpdated("i", GROUP, ANA, null, null, null, false));
    }

    @Test
    void createdGroupBecomesGroupsUpsert() {
        var events = CobaltEvents.groupNotification("i", gp2(node("create", Map.of(),
                node("group", Map.of("id", "120363000000000001", "subject", "Família", "creator", ANA,
                                "creation", "1700000000"),
                        node("participant", Map.of("jid", ANA, "type", "superadmin")),
                        node("participant", Map.of("jid", "5561922222222@s.whatsapp.net")),
                        node("description", Map.of("id", "D1"), text("body", "Regras")),
                        node("announcement", Map.of())))), lids);

        assertThat(events).hasSize(1);
        var group = ((GroupsUpserted) events.getFirst()).groups().getFirst();
        assertThat(group.id()).isEqualTo(GROUP);
        assertThat(group.subject()).isEqualTo("Família");
        assertThat(group.owner()).isEqualTo(ANA);
        assertThat(group.creation()).isEqualTo(1_700_000_000L);
        assertThat(group.desc()).isEqualTo("Regras");
        assertThat(group.announce()).isTrue();
        assertThat(group.restrict()).isFalse();
        assertThat(group.participants()).containsExactly(new GroupParticipant(ANA, "superadmin"),
                new GroupParticipant("5561922222222@s.whatsapp.net", null));
    }

    @Test
    void otherNotificationsAreIgnored() {
        assertThat(CobaltEvents.groupNotification("i",
                node("notification", Map.of("from", ANA, "type", "w:gp2")), lids)).isEmpty();
        assertThat(CobaltEvents.groupNotification("i",
                node("notification", Map.of("from", GROUP, "type", "picture")), lids)).isEmpty();
        assertThat(CobaltEvents.groupNotification("i", node("message", Map.of("from", GROUP)), lids)).isEmpty();
    }
}

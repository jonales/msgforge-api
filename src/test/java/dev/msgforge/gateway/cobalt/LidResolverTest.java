package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.auties00.cobalt.stanza.model.Stanza;
import com.github.auties00.cobalt.stanza.model.StanzaBuilder;
import com.github.auties00.cobalt.wire.core.jid.Jid;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Formatos de nó vistos ao vivo (números fictícios). */
class LidResolverTest {

    private final LidResolver lids = new LidResolver();

    private static Stanza node(String description, Map<String, String> attributes) {
        var builder = new StanzaBuilder().description(description);
        attributes.forEach(builder::attribute);
        return builder.build();
    }

    @Test
    void learnsSenderPhoneFromDirectMessage() {
        lids.observe(node("message", Map.of("from", "111111111111111:16@lid", "type", "text",
                "id", "3EB0A", "sender_pn", "5561988887777@s.whatsapp.net")));

        assertThat(lids.apply(Jid.of("111111111111111:16@lid"))).isEqualTo("5561988887777@s.whatsapp.net");
        assertThat(lids.apply(Jid.of("111111111111111@lid"))).isEqualTo("5561988887777@s.whatsapp.net");
    }

    @Test
    void learnsParticipantPhoneInGroups() {
        lids.observe(node("message", Map.of("from", "120363000000000000@g.us", "id", "3EB0B",
                "participant", "222222222222222:29@lid", "addressing_mode", "lid",
                "participant_pn", "5561977776666@s.whatsapp.net")));

        assertThat(lids.apply(Jid.of("222222222222222@lid"))).isEqualTo("5561977776666@s.whatsapp.net");
        assertThat(lids.apply(Jid.of("120363000000000000@g.us"))).isEqualTo("120363000000000000@g.us");
    }

    @Test
    void unknownLidIsNeverDisguisedAsPhoneNumber() {
        assertThat(lids.apply(Jid.of("333333333333333:16@lid"))).isEqualTo("333333333333333@lid");
    }

    @Test
    void fallsBackToTheCobaltStore() {
        var withStore = new LidResolver(lid -> lid.user().equals("444444444444444")
                ? Optional.of(Jid.of("5561966665555@s.whatsapp.net"))
                : Optional.empty());

        assertThat(withStore.apply(Jid.of("444444444444444:3@lid"))).isEqualTo("5561966665555@s.whatsapp.net");
        assertThat(withStore.apply(Jid.of("555555555555555@lid"))).isEqualTo("555555555555555@lid");
    }

    @Test
    void phoneJidsLoseTheDeviceAndOwnLidCanBeLearned() {
        assertThat(lids.apply(Jid.of("5561988887777:16@s.whatsapp.net"))).isEqualTo("5561988887777@s.whatsapp.net");

        lids.learn(Jid.of("444444444444444:16@lid"), Jid.of("5561966665555:16@s.whatsapp.net"));
        assertThat(lids.apply(Jid.of("444444444444444@lid"))).isEqualTo("5561966665555@s.whatsapp.net");
    }

    @Test
    void ignoresPairsThatAreNotLidToPhone() {
        lids.learn(Jid.of("5561988887777@s.whatsapp.net"), Jid.of("5561977776666@s.whatsapp.net"));
        lids.observe(node("receipt", Map.of("from", "555555555555555@lid",
                "sender_pn", "5561988887777@s.whatsapp.net")));

        assertThat(lids.apply(Jid.of("5561988887777@s.whatsapp.net"))).isEqualTo("5561988887777@s.whatsapp.net");
        assertThat(lids.apply(Jid.of("555555555555555@lid"))).isEqualTo("555555555555555@lid");
    }
}

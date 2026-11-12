package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;

import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.node.Node;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Formatos vistos no teste ao vivo (números fictícios). */
class LidResolverTest {

    private final LidResolver lids = new LidResolver();

    private static Node message(Map<String, Object> attributes) {
        return Node.of("message", attributes, new Node[0]);
    }

    @Test
    void learnsSenderPhoneFromDirectMessage() {
        lids.observe(message(Map.of("from", "111111111111111_1:16@s.whatsapp.net", "type", "text",
                "id", "3EB0A", "sender_pn", "5561988887777@s.whatsapp.net")));

        assertThat(lids.apply(Jid.of("111111111111111_1:16@s.whatsapp.net"))).isEqualTo("5561988887777@s.whatsapp.net");
        assertThat(lids.apply(Jid.of("111111111111111@lid"))).isEqualTo("5561988887777@s.whatsapp.net");
    }

    @Test
    void learnsParticipantPhoneInGroups() {
        lids.observe(message(Map.of("from", "120363000000000000@g.us", "id", "3EB0B",
                "participant", "222222222222222_1:29@s.whatsapp.net", "addressing_mode", "lid",
                "participant_pn", "5561977776666@s.whatsapp.net")));

        assertThat(lids.apply(Jid.of("222222222222222_1@s.whatsapp.net"))).isEqualTo("5561977776666@s.whatsapp.net");
        assertThat(lids.apply(Jid.of("120363000000000000@g.us"))).isEqualTo("120363000000000000@g.us");
    }

    @Test
    void unknownLidIsNeverDisguisedAsPhoneNumber() {
        // toSimpleJid() descarta o agente: 333..._1 viraria 333...@s.whatsapp.net, um "telefone" falso
        assertThat(lids.apply(Jid.of("333333333333333_1:16@s.whatsapp.net"))).isEqualTo("333333333333333@lid");
    }

    @Test
    void phoneJidsLoseTheDeviceAndOwnLidCanBeLearned() {
        assertThat(lids.apply(Jid.of("5561988887777:16@s.whatsapp.net"))).isEqualTo("5561988887777@s.whatsapp.net");

        lids.learn(Jid.of("444444444444444:16@lid"), Jid.of("5561966665555:16@s.whatsapp.net"));
        assertThat(lids.apply(Jid.of("444444444444444_1@s.whatsapp.net"))).isEqualTo("5561966665555@s.whatsapp.net");
    }

    @Test
    void ignoresPairsThatAreNotLidToPhone() {
        lids.learn(Jid.of("5561988887777@s.whatsapp.net"), Jid.of("5561977776666@s.whatsapp.net"));
        lids.observe(Node.of("receipt", Map.of("from", "555555555555555_1@s.whatsapp.net",
                "sender_pn", "5561988887777@s.whatsapp.net"), new Node[0]));

        assertThat(lids.apply(Jid.of("5561988887777@s.whatsapp.net"))).isEqualTo("5561988887777@s.whatsapp.net");
        assertThat(lids.apply(Jid.of("555555555555555_1@s.whatsapp.net"))).isEqualTo("555555555555555@lid");
    }
}

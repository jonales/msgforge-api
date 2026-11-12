package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.auties00.cobalt.wire.core.jid.Jid;
import com.github.auties00.cobalt.wire.core.message.MessageKeyBuilder;
import com.github.auties00.cobalt.wire.core.message.MessageStatus;
import com.github.auties00.cobalt.wire.linked.chat.ChatMessageInfo;
import com.github.auties00.cobalt.wire.linked.chat.ChatMessageInfoBuilder;
import com.github.auties00.cobalt.wire.linked.message.LinkedMessageContainer;
import com.github.auties00.cobalt.wire.linked.message.media.DocumentMessageBuilder;
import com.github.auties00.cobalt.wire.linked.message.media.ImageMessageBuilder;
import com.github.auties00.cobalt.wire.linked.message.poll.PollCreationMessageBuilder;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mensagens montadas com os builders reais do Cobalt, offline. */
class CobaltMessageMapperTest {

    private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static ChatMessageInfo message(String chat, String sender, boolean fromMe, LinkedMessageContainer content) {
        var key = new MessageKeyBuilder().parentJid(Jid.of(chat)).fromMe(fromMe).id("3EB0C431C26A1916E07E00").build();
        var builder = new ChatMessageInfoBuilder().key(key).message(content)
                .timestamp(Instant.ofEpochSecond(1_700_000_000L))
                .pushName("Fulano").status(MessageStatus.DELIVERED);
        if (sender != null) {
            builder.senderJid(Jid.of(sender));
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @Test
    void privateTextMessageHasTheContractShape() {
        var data = CobaltMessageMapper.upsert(
                message("5561999999999@s.whatsapp.net", null, false, LinkedMessageContainer.of("oi")), INSTANCE).orElseThrow();

        assertThat(map(data.get("key"))).containsExactly(
                Map.entry("remoteJid", "5561999999999@s.whatsapp.net"),
                Map.entry("fromMe", false),
                Map.entry("id", "3EB0C431C26A1916E07E00"));
        assertThat(data).containsEntry("pushName", "Fulano")
                .containsEntry("status", "DELIVERY_ACK")
                .containsEntry("messageType", "conversation")
                .containsEntry("messageTimestamp", 1_700_000_000L)
                .containsEntry("instanceId", INSTANCE.toString())
                .containsEntry("source", "web");
        // Como no Baileys: "conversation" é uma string, não um objeto
        assertThat(map(data.get("message"))).containsEntry("conversation", "oi");
    }

    @Test
    void groupMessagesCarryTheParticipant() {
        var data = CobaltMessageMapper.upsert(message("120363000000000000@g.us", "5561988887777@s.whatsapp.net",
                false, LinkedMessageContainer.of("oi grupo")), INSTANCE).orElseThrow();

        assertThat(map(data.get("key"))).containsEntry("participant", "5561988887777@s.whatsapp.net");
    }

    @Test
    void lidJidsAreTranslatedToThePhoneNumber() {
        var lids = new LidResolver();
        lids.learn(Jid.of("111111111111111@lid"), Jid.of("5561999999999@s.whatsapp.net"));
        lids.learn(Jid.of("222222222222222@lid"), Jid.of("5561988887777@s.whatsapp.net"));

        var direct = CobaltMessageMapper.upsert(message("111111111111111@lid",
                "111111111111111:16@lid", false, LinkedMessageContainer.of("oi")), INSTANCE, lids).orElseThrow();
        var group = CobaltMessageMapper.upsert(message("120363000000000000@g.us",
                "222222222222222:29@lid", false, LinkedMessageContainer.of("oi grupo")), INSTANCE, lids).orElseThrow();

        assertThat(map(direct.get("key"))).containsEntry("remoteJid", "5561999999999@s.whatsapp.net");
        assertThat(map(group.get("key"))).containsEntry("remoteJid", "120363000000000000@g.us")
                .containsEntry("participant", "5561988887777@s.whatsapp.net");
    }

    @Test
    void mediaMessagesExposeCaptionMimetypeAndFileName() {
        var image = new ImageMessageBuilder().caption("foto").mimetype("image/png").build();
        var data = CobaltMessageMapper.upsert(message("5561999999999@s.whatsapp.net", null, true,
                LinkedMessageContainer.of(image)), INSTANCE).orElseThrow();
        assertThat(data).containsEntry("messageType", "imageMessage");
        assertThat(map(map(data.get("message")).get("imageMessage")))
                .containsEntry("caption", "foto").containsEntry("mimetype", "image/png");
        assertThat(map(data.get("key"))).containsEntry("fromMe", true);

        var document = new DocumentMessageBuilder().fileName("contrato.pdf").mimetype("application/pdf").build();
        var doc = CobaltMessageMapper.upsert(message("5561999999999@s.whatsapp.net", null, false,
                LinkedMessageContainer.of(document)), INSTANCE).orElseThrow();
        assertThat(map(map(doc.get("message")).get("documentMessage")))
                .containsEntry("fileName", "contrato.pdf").containsEntry("mimetype", "application/pdf");
    }

    @Test
    void statusUpdatesUseTheContractNames() {
        var data = CobaltMessageMapper.statusUpdate(
                message("120363000000000000@g.us", "5561988887777@s.whatsapp.net", true, LinkedMessageContainer.of("x")),
                INSTANCE).orElseThrow();
        assertThat(data).containsEntry("keyId", "3EB0C431C26A1916E07E00")
                .containsEntry("remoteJid", "120363000000000000@g.us")
                .containsEntry("fromMe", true)
                .containsEntry("participant", "5561988887777@s.whatsapp.net")
                .containsEntry("status", "DELIVERY_ACK")
                .containsEntry("instanceId", INSTANCE.toString());

        assertThat(CobaltMessageMapper.status(MessageStatus.SERVER_ACK)).isEqualTo("SERVER_ACK");
        assertThat(CobaltMessageMapper.status(MessageStatus.READ)).isEqualTo("READ");
        assertThat(CobaltMessageMapper.status(MessageStatus.PLAYED)).isEqualTo("PLAYED");
        assertThat(CobaltMessageMapper.status(null)).isEqualTo("PENDING");
    }

    @Test
    void genericNamesAndDeviceHeuristicFollowBaileys() {
        assertThat(CobaltMessageMapper.genericName(new PollCreationMessageBuilder().name("x").build()))
                .isEqualTo("pollCreationMessage");
        assertThat(CobaltMessageMapper.device("3A1234567890ABCDEF12")).isEqualTo("ios");
        assertThat(CobaltMessageMapper.device("3EB0C431C26A1916E07E00")).isEqualTo("web");
        assertThat(CobaltMessageMapper.device("ABCDEF0123456789ABCDEF0123456789")).isEqualTo("android");
        assertThat(CobaltMessageMapper.device(null)).isEqualTo("unknown");
    }
}

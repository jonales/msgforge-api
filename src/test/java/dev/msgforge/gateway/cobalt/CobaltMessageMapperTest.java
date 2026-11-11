package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;

import it.auties.whatsapp.model.info.ChatMessageInfo;
import it.auties.whatsapp.model.info.ChatMessageInfoBuilder;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.message.model.ChatMessageKeyBuilder;
import it.auties.whatsapp.model.message.model.Message;
import it.auties.whatsapp.model.message.model.MessageContainer;
import it.auties.whatsapp.model.message.model.MessageStatus;
import it.auties.whatsapp.model.message.standard.DocumentMessageSimpleBuilder;
import it.auties.whatsapp.model.message.standard.ImageMessageSimpleBuilder;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mensagens montadas com os builders reais do Cobalt, offline. */
class CobaltMessageMapperTest {

    private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static ChatMessageInfo message(String chat, String sender, boolean fromMe, MessageContainer content) {
        var key = new ChatMessageKeyBuilder().chatJid(Jid.of(chat)).fromMe(fromMe).id("3EB0C431C26A1916E07E00").build();
        var builder = new ChatMessageInfoBuilder().key(key).message(content).timestampSeconds(1_700_000_000L)
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
    void privateTextMessageHasTheEvolutionShape() {
        var data = CobaltMessageMapper.upsert(
                message("5561999999999@s.whatsapp.net", null, false, MessageContainer.of("oi")), INSTANCE).orElseThrow();

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
                false, MessageContainer.of("oi grupo")), INSTANCE).orElseThrow();

        assertThat(map(data.get("key"))).containsEntry("participant", "5561988887777@s.whatsapp.net");
    }

    @Test
    void mediaMessagesExposeCaptionMimetypeAndFileName() {
        var image = new ImageMessageSimpleBuilder().media(new byte[] {1, 2, 3}).caption("foto").mimeType("image/png").build();
        var data = CobaltMessageMapper.upsert(message("5561999999999@s.whatsapp.net", null, true,
                MessageContainer.of(image)), INSTANCE).orElseThrow();
        assertThat(data).containsEntry("messageType", "imageMessage");
        assertThat(map(map(data.get("message")).get("imageMessage")))
                .containsEntry("caption", "foto").containsEntry("mimetype", "image/png");
        assertThat(map(data.get("key"))).containsEntry("fromMe", true);

        var document = new DocumentMessageSimpleBuilder().media("%PDF-1.4".getBytes()).fileName("contrato.pdf")
                .mimeType("application/pdf").build();
        var doc = CobaltMessageMapper.upsert(message("5561999999999@s.whatsapp.net", null, false,
                MessageContainer.of(document)), INSTANCE).orElseThrow();
        assertThat(map(map(doc.get("message")).get("documentMessage")))
                .containsEntry("fileName", "contrato.pdf").containsEntry("mimetype", "application/pdf");
    }

    @Test
    void statusUpdatesUseTheEvolutionNames() {
        var data = CobaltMessageMapper.statusUpdate(
                message("120363000000000000@g.us", "5561988887777@s.whatsapp.net", true, MessageContainer.of("x")),
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
        assertThat(CobaltMessageMapper.genericName(Message.Type.POLL_CREATION)).isEqualTo("pollCreationMessage");
        assertThat(CobaltMessageMapper.genericName(Message.Type.TEXT)).isEqualTo("textMessage");
        assertThat(CobaltMessageMapper.device("3A1234567890ABCDEF12")).isEqualTo("ios");
        assertThat(CobaltMessageMapper.device("3EB0C431C26A1916E07E00")).isEqualTo("web");
        assertThat(CobaltMessageMapper.device("ABCDEF0123456789ABCDEF0123456789")).isEqualTo("android");
        assertThat(CobaltMessageMapper.device(null)).isEqualTo("unknown");
    }
}

package dev.msgforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.web.MessageContent.Audio;
import dev.msgforge.web.MessageContent.Contact;
import dev.msgforge.web.MessageContent.Document;
import dev.msgforge.web.MessageContent.Image;
import dev.msgforge.web.MessageContent.Location;
import dev.msgforge.web.MessageContent.Poll;
import dev.msgforge.web.MessageContent.Reaction;
import dev.msgforge.web.MessageContent.Revoked;
import dev.msgforge.web.MessageContent.Sticker;
import dev.msgforge.web.MessageContent.Text;
import dev.msgforge.web.MessageContent.Unknown;
import dev.msgforge.web.MessageContent.Video;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Cada tipo gravado no histórico (formato Baileys) vira o seu jeito de aparecer na conversa. */
class MessageContentTest {

    @Test
    void textComesFromConversationOrExtendedText() {
        assertThat(MessageContent.of("conversation", Map.of("conversation", "oi"))).isEqualTo(new Text("oi"));
        assertThat(MessageContent.of("extendedTextMessage", Map.of("extendedTextMessage", Map.of("text", "link"))))
                .isEqualTo(new Text("link"));
    }

    @Test
    void imageAndVideoKeepCaptionDurationAndSize() {
        var image = MessageContent.of("imageMessage", Map.of("imageMessage",
                Map.of("caption", "foto", "mimetype", "image/jpeg", "fileLength", 2048)));
        assertThat(image).isEqualTo(new Image("foto", "image/jpeg", 2048L));
        assertThat(image.media()).isTrue();
        assertThat(image.summary()).isEqualTo("foto");

        var video = (Video) MessageContent.of("videoMessage", Map.of("videoMessage",
                Map.of("seconds", 75, "gifPlayback", true, "fileLength", "123456")));   // Long como texto (Baileys)
        assertThat(video.duration()).isEqualTo("1:15");
        assertThat(video.gif()).isTrue();
        assertThat(video.labelKey()).isEqualTo("gif");
        assertThat(video.size()).isEqualTo(123456L);
    }

    @Test
    void voiceNoteIsAnAudioWithItsOwnLabel() {
        var voice = (Audio) MessageContent.of("audioMessage", Map.of("audioMessage", Map.of("ptt", true, "seconds", 9)));
        assertThat(voice.voice()).isTrue();
        assertThat(voice.labelKey()).isEqualTo("voice");
        assertThat(voice.duration()).isEqualTo("0:09");
        // Enviada pela API: o histórico só tem o ptt
        var sent = (Audio) MessageContent.of("audioMessage", Map.of("audioMessage", Map.of("ptt", true)));
        assertThat(sent.duration()).isNull();
        assertThat(((Audio) MessageContent.of("audioMessage", Map.of("audioMessage", Map.of()))).labelKey())
                .isEqualTo("audio");
    }

    @Test
    void documentShowsNameAndSize() {
        var doc = (Document) MessageContent.of("documentMessage", Map.of("documentMessage",
                Map.of("fileName", "contrato.pdf", "mimetype", "application/pdf", "fileLength", 1_468_006)));
        assertThat(doc.fileName()).isEqualTo("contrato.pdf");
        assertThat(doc.sizeLabel()).isEqualTo("1,4 MB");
        assertThat(doc.summary()).isEqualTo("contrato.pdf");
        assertThat(MessageContent.size(820L)).isEqualTo("820 B");
        assertThat(MessageContent.size(12_000L)).isEqualTo("12 KB");
        assertThat(MessageContent.size(null)).isNull();
    }

    @Test
    void stickerLocationAndContacts() {
        assertThat(MessageContent.of("stickerMessage", Map.of("stickerMessage", Map.of("isAnimated", true))))
                .isEqualTo(new Sticker(true));

        var place = (Location) MessageContent.of("locationMessage", Map.of("locationMessage",
                Map.of("degreesLatitude", -15.7801, "degreesLongitude", -47.9292, "name", "Escritório")));
        assertThat(place.coordinates()).isEqualTo("-15.78010, -47.92920");
        assertThat(place.mapUrl()).startsWith("https://www.openstreetmap.org/?mlat=-15.780100&mlon=-47.929200");
        assertThat(((Location) MessageContent.of("locationMessage", Map.of("locationMessage", Map.of()))).mapUrl()).isNull();

        assertThat(MessageContent.of("contactMessage", Map.of("contactMessage", Map.of("displayName", "Ana"))))
                .isEqualTo(new Contact(List.of("Ana")));
        assertThat(MessageContent.of("contactsArrayMessage", Map.of("contactsArrayMessage", Map.of(
                "displayName", "2 contacts",
                "contacts", List.of(Map.of("displayName", "Ana"), Map.of("displayName", "Bia"))))))
                .isEqualTo(new Contact(List.of("Ana", "Bia")));
    }

    @Test
    void reactionPollAndRevoked() {
        assertThat(MessageContent.of("reactionMessage", Map.of("reactionMessage",
                Map.of("text", "👍", "key", Map.of("id", "ABC")))))
                .isEqualTo(new Reaction("👍", "ABC"));
        assertThat(((Reaction) MessageContent.of("reactionMessage", Map.of("reactionMessage", Map.of("text", ""))))
                .removed()).isTrue();

        var poll = (Poll) MessageContent.of("pollCreationMessage", Map.of("pollCreationMessage", Map.of(
                "name", "Qual dia?", "options", List.of(Map.of("optionName", "Seg"), Map.of("optionName", "Ter")),
                "selectableOptionsCount", 0)));
        assertThat(poll.options()).containsExactly("Seg", "Ter");
        assertThat(poll.multiple()).isTrue();
        assertThat(((Poll) MessageContent.of("pollCreationMessage", Map.of("pollCreationMessage",
                Map.of("selectableOptionsCount", 1)))).multiple()).isFalse();

        assertThat(MessageContent.of("protocolMessage", Map.of("protocolMessage", Map.of("type", "REVOKE"))))
                .isEqualTo(new Revoked());
    }

    @Test
    void wrappersAreOpened() {
        var ephemeral = Map.<String, Object>of("ephemeralMessage", Map.of("message",
                Map.of("imageMessage", Map.of("caption", "some"))));
        assertThat(MessageContent.of("ephemeralMessage", ephemeral)).isEqualTo(new Image("some", null, null));
    }

    @Test
    void unknownTypesGetANeutralLabelAndNeverAnEmptyBubble() {
        assertThat(MessageContent.of("buttonsResponseMessage", Map.of("buttonsResponseMessage", Map.of())))
                .isEqualTo(new Unknown("buttonsResponse"));
        assertThat(MessageContent.of("protocolMessage", Map.of("protocolMessage", Map.of("type", "EPHEMERAL_SETTING"))))
                .isEqualTo(new Unknown("protocol"));
        assertThat(MessageContent.of(null, null)).isEqualTo(new Unknown("?"));
        assertThat(MessageContent.of("unknown", Map.of())).isEqualTo(new Unknown("unknown"));
        // Tipo da linha diferente da chave guardada: vale a chave
        assertThat(MessageContent.of("unknown", Map.of("conversation", "oi"))).isEqualTo(new Text("oi"));
    }
}

package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.config.MsgforgeProperties;
import dev.msgforge.event.GatewayEvent.ConnectionUpdated;
import dev.msgforge.gateway.WhatsAppGateway.OutgoingMedia;
import dev.msgforge.gateway.WhatsAppGateway.OutgoingText;
import dev.msgforge.instance.ConnectionState;
import dev.msgforge.instance.Instance;
import dev.msgforge.security.PrivateNetworkGuard;
import com.github.auties00.cobalt.wire.core.jid.Jid;
import com.github.auties00.cobalt.wire.linked.chat.group.GroupMetadataBuilder;
import com.github.auties00.cobalt.wire.linked.chat.group.GroupParticipant;
import com.github.auties00.cobalt.wire.linked.chat.group.GroupParticipantBuilder;
import com.github.auties00.cobalt.wire.linked.chat.group.GroupPartipantRole;
import com.github.auties00.cobalt.wire.linked.contact.ContactStatus;
import com.github.auties00.cobalt.wire.linked.message.contact.ContactMessage;
import com.github.auties00.cobalt.wire.linked.message.contact.ContactsArrayMessage;
import com.github.auties00.cobalt.wire.linked.message.location.LocationMessage;
import com.github.auties00.cobalt.wire.linked.message.media.AudioMessage;
import com.github.auties00.cobalt.wire.linked.message.media.DocumentMessage;
import com.github.auties00.cobalt.wire.linked.message.media.ImageMessage;
import com.github.auties00.cobalt.wire.linked.message.media.VideoMessage;
import com.github.auties00.cobalt.wire.linked.message.text.ReactionMessage;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Sem rede com o WhatsApp: o connect passa por um proxy numa porta fechada, então falha igual em
 * qualquer máquina (inclusive no CI, que tem internet e não deve falar com o WhatsApp).
 */
class CobaltWhatsAppGatewayTest {

    private static final dev.msgforge.gateway.media.AudioTranscoder TRANSCODER =
            new dev.msgforge.gateway.media.AudioTranscoder(new dev.msgforge.gateway.media.MediaProperties("ffmpeg", 30000, 1 << 24));

    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final InMemorySessionStore sessions = new InMemorySessionStore();
    private HttpServer server;
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path sessionDir;

    private static String deadProxy() {
        try (var socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();   // fechada ao sair do try
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private CobaltWhatsAppGateway gateway(boolean blockPrivateNetworks, long mediaMaxBytes) {
        var properties = new MsgforgeProperties("http://localhost", null,
                new MsgforgeProperties.Webhook(null, false, 2000, new MsgforgeProperties.Retry(1, 10, 10), 10,
                        blockPrivateNetworks),
                null, "cobalt",
                new MsgforgeProperties.Cobalt("Msgforge", 3000, 3000, mediaMaxBytes, deadProxy(), sessionDir.toString()));
        return new CobaltWhatsAppGateway(events::add, sessions, new PrivateNetworkGuard(blockPrivateNetworks), TRANSCODER, properties);
    }

    private static Instance instance(String name) {
        return new Instance(name, "TOKEN-" + name, "WHATSAPP-BAILEYS", null);
    }

    private String serve(int status, byte[] body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/arquivo.png";
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void connectFailureClosesTheInstanceAndReturnsWithoutCode() {
        var gateway = gateway(false, 1024);
        var start = System.nanoTime();

        var result = gateway.connect(instance("falha"), null);

        assertThat((System.nanoTime() - start) / 1_000_000).as("não espera o qrWaitMs inteiro").isLessThan(2900);
        assertThat(result.qrCode()).isNull();
        assertThat(result.pairingCode()).isNull();
        assertThat(events).filteredOn(ConnectionUpdated.class::isInstance)
                .extracting(e -> ((ConnectionUpdated) e).state(), e -> ((ConnectionUpdated) e).statusReason())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(ConnectionState.CONNECTING, null),
                        org.assertj.core.groups.Tuple.tuple(ConnectionState.CLOSE, CobaltWhatsAppGateway.REASON_CONNECTION_CLOSED));
        assertThatThrownBy(() -> gateway.sendText(instance("falha"), new OutgoingText("5561999999999@s.whatsapp.net", "oi", null, null)))
                .as("a conexão que falhou não fica registrada").isInstanceOf(BadRequestException.class);
    }

    /** Proxy que aceita a conexão e nunca responde: o connect fica pendente, como esperando o QR. */
    private static java.net.ServerSocket silentProxy() throws Exception {
        var server = new java.net.ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            var held = new java.util.ArrayList<java.net.Socket>();
            while (!server.isClosed()) {
                try {
                    held.add(server.accept());   // segura o socket aberto, sem responder
                } catch (java.io.IOException e) {
                    return;
                }
            }
        });
        return server;
    }

    @Test
    void concurrentConnectsShareOnePendingConnection() throws Exception {
        try (var proxy = silentProxy()) {
            var properties = new MsgforgeProperties("http://localhost", null,
                    new MsgforgeProperties.Webhook(null, false, 2000, new MsgforgeProperties.Retry(1, 10, 10), 10, false),
                    null, "cobalt",
                    new MsgforgeProperties.Cobalt("Msgforge", 300, 3000, 1024,
                            "http://127.0.0.1:" + proxy.getLocalPort(), sessionDir.toString()));
            var gateway = new CobaltWhatsAppGateway(events::add, sessions, new PrivateNetworkGuard(false), TRANSCODER, properties);
            var instance = instance("corrida");
            try {
                var start = new java.util.concurrent.CountDownLatch(1);
                try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    for (int i = 0; i < 10; i++) {
                        pool.submit(() -> {
                            start.await();
                            return gateway.connect(instance, null);
                        });
                    }
                    start.countDown();
                }
                // Sem o lock, cada chamada criava (e publicava) um cliente do WhatsApp para a mesma sessão
                assertThat(gateway.activeConnections()).isEqualTo(1);
                assertThat(events).filteredOn(ConnectionUpdated.class::isInstance).hasSize(1);
            } finally {
                gateway.destroy();
            }
        }
    }

    @Test
    void sendingWithoutConnectionIsABadRequest() {
        var gateway = gateway(false, 1024);
        assertThatThrownBy(() -> gateway.sendText(instance("vendas"),
                new OutgoingText("5561999999999@s.whatsapp.net", "oi", null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("not connected");
    }

    @Test
    void logoutWithoutConnectionStillPublishesClose() {
        gateway(false, 1024).logout(instance("vendas"));

        assertThat(events).singleElement().isInstanceOfSatisfying(ConnectionUpdated.class, e -> {
            assertThat(e.state()).isEqualTo(ConnectionState.CLOSE);
            assertThat(e.statusReason()).isEqualTo(CobaltWhatsAppGateway.REASON_LOGGED_OUT);
        });
    }

    @Test
    void mediaComesFromBase64DataUrlOrHttp() throws Exception {
        var gateway = gateway(false, 1024);
        var bytes = new byte[] {1, 2, 3, 4};
        var base64 = Base64.getEncoder().encodeToString(bytes);

        assertThat(gateway.loadMedia(base64)).containsExactly(bytes);
        assertThat(gateway.loadMedia("data:image/png;base64," + base64)).containsExactly(bytes);
        assertThat(gateway.loadMedia(serve(200, bytes))).containsExactly(bytes);
    }

    @Test
    void invalidOrOversizedMediaIsABadRequest() throws Exception {
        var gateway = gateway(false, 4);

        assertThatThrownBy(() -> gateway.loadMedia("não é base64 %%%")).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> gateway.loadMedia(Base64.getEncoder().encodeToString(new byte[5])))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("larger");
        assertThatThrownBy(() -> gateway.loadMedia(serve(200, new byte[5])))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("larger");
        stop();
        assertThatThrownBy(() -> gateway.loadMedia(serve(404, new byte[0])))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("404");
    }

    @Test
    void mediaUrlsArePrivateNetworkCheckedWhenTheGuardIsOn() throws Exception {
        var url = serve(200, new byte[] {1});
        assertThatThrownBy(() -> gateway(true, 1024).loadMedia(url))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("not allowed");
    }

    @Test
    void buildsEachMediaTypeAndRejectsUnknownOnes() {
        // Os bytes vão ao CDN depois (uploadMedia); aqui só o tipo e os metadados da mensagem
        assertThat(CobaltWhatsAppGateway.buildMedia(media("image"))).isInstanceOf(ImageMessage.class);
        assertThat(CobaltWhatsAppGateway.buildMedia(media("video"))).isInstanceOf(VideoMessage.class);
        assertThat(CobaltWhatsAppGateway.buildMedia(media("audio"))).isInstanceOf(AudioMessage.class);
        var document = (DocumentMessage) CobaltWhatsAppGateway.buildMedia(media("document"));
        assertThat(document.fileName()).contains("a.pdf");
        assertThatThrownBy(() -> CobaltWhatsAppGateway.buildMedia(media("sticker")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void buildsLocationContactsReactionPollAndVoiceMessages() {
        var jid = "5561999999999@s.whatsapp.net";

        var location = (LocationMessage) CobaltWhatsAppGateway.buildMessage(
                new dev.msgforge.gateway.WhatsAppGateway.Location(jid, -15.79, -47.88, "Esplanada", "Brasília", null));
        assertThat(location.degreesLatitude()).hasValue(-15.79);
        assertThat(location.degreesLongitude()).hasValue(-47.88);
        assertThat(location.name()).contains("Esplanada");

        var card = new dev.msgforge.gateway.WhatsAppGateway.ContactCard("Ana", "BEGIN:VCARD\nFN:Ana\nEND:VCARD");
        var one = CobaltWhatsAppGateway.buildMessage(
                new dev.msgforge.gateway.WhatsAppGateway.Contacts(jid, java.util.List.of(card), null));
        assertThat(one).isInstanceOf(ContactMessage.class);
        var many = CobaltWhatsAppGateway.buildMessage(
                new dev.msgforge.gateway.WhatsAppGateway.Contacts(jid, java.util.List.of(card, card), null));
        assertThat(many).isInstanceOf(ContactsArrayMessage.class);

        var reaction = (ReactionMessage) CobaltWhatsAppGateway.buildMessage(
                new dev.msgforge.gateway.WhatsAppGateway.Reaction(jid, "3EB0ABC", true, "👍"));
        assertThat(reaction.text()).contains("👍");
        var key = reaction.key().orElseThrow();
        assertThat(key.id()).contains("3EB0ABC");
        assertThat(key.fromMe()).isTrue();
        assertThat(key.parentJid().orElseThrow().toString()).isEqualTo(jid);

        var voice = (AudioMessage) CobaltWhatsAppGateway.buildMessage(
                new dev.msgforge.gateway.WhatsAppGateway.VoiceNote(jid, "x", null, false));
        assertThat(voice.ptt()).isTrue();
        assertThat(voice.mimetype()).contains("audio/ogg; codecs=opus");
    }

    @Test
    void pollsKeepTheRequestedSelectableCount() {
        var single = CobaltWhatsAppGateway.poll(new dev.msgforge.gateway.WhatsAppGateway.Poll(
                "5561999999999@s.whatsapp.net", "Almoço?", 1, java.util.List.of("Sim", "Não"), null));

        assertThat(single.name()).contains("Almoço?");
        assertThat(single.selectableOptionsCount()).hasValue(1);
        assertThat(single.options()).extracting(o -> o.optionName().orElse(null)).containsExactly("Sim", "Não");
        assertThat(single.encKey()).isPresent();
    }

    @Test
    void voiceNoteIsSentAsIsWhenEncodingIsOffOrAlreadyOggOpus() {
        var gateway = gateway(false, 1 << 20);
        var raw = "qualquer coisa".getBytes();
        var ogg = new byte[64];
        System.arraycopy("OggS".getBytes(), 0, ogg, 0, 4);
        System.arraycopy("OpusHead".getBytes(), 0, ogg, 28, 8);

        assertThat(gateway.voiceAudio(voice(raw, false))).isEqualTo(raw);
        assertThat(gateway.voiceAudio(voice(ogg, true))).isEqualTo(ogg);
    }

    @Test
    void voiceNoteIsConvertedToOggOpusWhenEncoding() {
        org.junit.jupiter.api.Assumptions.assumeTrue(TRANSCODER.available(), "ffmpeg não instalado");
        var wav = dev.msgforge.gateway.media.AudioTranscoderTestSupport.wav();

        var converted = gateway(false, 1 << 20).voiceAudio(voice(wav, true));

        assertThat(dev.msgforge.gateway.media.AudioTranscoder.isOggOpus(converted)).isTrue();
    }

    private static dev.msgforge.gateway.WhatsAppGateway.VoiceNote voice(byte[] audio, boolean encode) {
        return new dev.msgforge.gateway.WhatsAppGateway.VoiceNote("5561999999999@s.whatsapp.net",
                Base64.getEncoder().encodeToString(audio), null, encode);
    }

    @Test
    void chatAndProfileOperationsNeedAConnectedInstance() {
        var gateway = gateway(false, 1 << 20);
        var instance = instance("desconectada");
        var jid = "5561999999999@s.whatsapp.net";
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls = List.of(
                () -> gateway.checkNumbers(instance, List.of(jid)),
                () -> gateway.markRead(instance, List.of(new dev.msgforge.gateway.WhatsAppGateway.MessageKey(jid, "X", false))),
                () -> gateway.sendPresence(instance, jid, dev.msgforge.gateway.WhatsAppGateway.Presence.COMPOSING, null),
                () -> gateway.fetchProfile(instance, jid),
                () -> gateway.profilePictureUrl(instance, jid),
                () -> gateway.updateProfile(instance, new dev.msgforge.gateway.WhatsAppGateway.ProfileName("Ana")));
        for (var call : calls) {
            assertThatThrownBy(call).isInstanceOf(BadRequestException.class).hasMessageContaining("not connected");
        }
    }

    private static GroupParticipant participant(Jid jid, GroupPartipantRole role) {
        return new GroupParticipantBuilder().userJid(jid).rank(role).build();
    }

    @Test
    void groupMetadataBecomesContractGroup() {
        var founder = Jid.of("5561911111111@s.whatsapp.net");
        var admin = Jid.of("5561922222222@s.whatsapp.net");
        var member = Jid.of("5561933333333@s.whatsapp.net");
        var participants = new java.util.LinkedHashSet<GroupParticipant>();
        participants.add(participant(founder, GroupPartipantRole.FOUNDER));
        participants.add(participant(admin, GroupPartipantRole.ADMIN));
        participants.add(participant(member, GroupPartipantRole.MEMBER));
        var metadata = new GroupMetadataBuilder()
                .jid(Jid.of("120363000000000001@g.us"))
                .subject("Família")
                .subjectAuthorJid(admin)
                .subjectTimestamp(java.time.Instant.ofEpochSecond(1_700_000_100L))
                .foundationTimestamp(java.time.Instant.ofEpochSecond(1_700_000_000L))
                .founderJid(founder)
                .description("Regras")
                .announce(true)
                .restrict(false)
                .participants(participants)
                .build();

        var group = CobaltWhatsAppGateway.groupInfo(metadata, new LidResolver());

        assertThat(group.id()).isEqualTo("120363000000000001@g.us");
        assertThat(group.subject()).isEqualTo("Família");
        assertThat(group.subjectOwner()).isEqualTo("5561922222222@s.whatsapp.net");
        assertThat(group.subjectTime()).isEqualTo(1_700_000_100L);
        assertThat(group.creation()).isEqualTo(1_700_000_000L);
        assertThat(group.owner()).isEqualTo("5561911111111@s.whatsapp.net");
        assertThat(group.desc()).isEqualTo("Regras");
        assertThat(group.announce()).isTrue();
        assertThat(group.restrict()).isFalse();
        assertThat(group.participants()).extracting(p -> p.id() + "=" + p.admin()).containsExactly(
                "5561911111111@s.whatsapp.net=superadmin", "5561922222222@s.whatsapp.net=admin",
                "5561933333333@s.whatsapp.net=null");
    }

    @Test
    void groupParticipantsByLidNeverBecomeFakePhones() {
        var known = Jid.of("111111111111111@lid");
        var unknown = Jid.of("222222222222222@lid");
        var participants = new java.util.LinkedHashSet<GroupParticipant>();
        participants.add(participant(known, GroupPartipantRole.FOUNDER));
        participants.add(participant(unknown, GroupPartipantRole.MEMBER));
        var metadata = new GroupMetadataBuilder()
                .jid(Jid.of("120363000000000001@g.us"))
                .subject("G").founderJid(known)
                .participants(participants)
                .build();
        var lids = new LidResolver();
        lids.learn(known, Jid.of("5561911111111@s.whatsapp.net"));

        var group = CobaltWhatsAppGateway.groupInfo(metadata, lids);

        assertThat(group.owner()).isEqualTo("5561911111111@s.whatsapp.net");
        assertThat(group.participants()).extracting(p -> p.id())
                .containsExactly("5561911111111@s.whatsapp.net", "222222222222222@lid");
    }

    @Test
    void groupOperationsNeedAConnectedInstance() {
        var gateway = gateway(false, 1 << 20);
        var instance = instance("desconectada");
        var group = "120363000000000001@g.us";
        List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> calls = List.of(
                () -> gateway.createGroup(instance, "G", null, List.of("5561999999999@s.whatsapp.net")),
                () -> gateway.groupInfo(instance, group),
                () -> gateway.groups(instance),
                () -> gateway.groupInviteCode(instance, group),
                () -> gateway.revokeGroupInvite(instance, group),
                () -> gateway.acceptGroupInvite(instance, "ABCDEFGHIJ"),
                () -> gateway.updateParticipants(instance, group,
                        dev.msgforge.gateway.WhatsAppGateway.ParticipantAction.ADD, List.of("5561999999999@s.whatsapp.net")),
                () -> gateway.updateGroup(instance, group, new dev.msgforge.gateway.WhatsAppGateway.GroupSubject("x")),
                () -> gateway.leaveGroup(instance, group));
        for (var call : calls) {
            assertThatThrownBy(call).isInstanceOf(BadRequestException.class).hasMessageContaining("not connected");
        }
    }

    @Test
    void numbersAnsweredWithoutTheNinthDigitStillExist() {
        var withNine = "5561999998888@s.whatsapp.net";
        var answered = "556199998888@s.whatsapp.net";   // a conta antiga, registrada sem o 9
        var missing = "5561977776666@s.whatsapp.net";

        var result = CobaltWhatsAppGateway.resolveNumbers(List.of(withNine, missing),
                java.util.Map.of(answered, true, missing, false));

        assertThat(result).containsEntry(withNine, answered);
        assertThat(result).containsEntry(missing, null);
        assertThat(result.keySet()).containsExactly(withNine, missing);
    }

    @Test
    void pausedPresenceEndsTyping() {
        assertThat(CobaltWhatsAppGateway.contactStatus(dev.msgforge.gateway.WhatsAppGateway.Presence.PAUSED))
                .isEqualTo(ContactStatus.AVAILABLE);
        assertThat(CobaltWhatsAppGateway.contactStatus(dev.msgforge.gateway.WhatsAppGateway.Presence.RECORDING))
                .isEqualTo(ContactStatus.RECORDING);
    }

    @Test
    void pairingNumbersAreDigitsOnly() {
        assertThat(CobaltWhatsAppGateway.pairingNumber("+55 (61) 99999-9999")).isEqualTo(5561999999999L);
        assertThatThrownBy(() -> CobaltWhatsAppGateway.pairingNumber("123")).isInstanceOf(BadRequestException.class);
    }

    private static byte[] resource(String path) {
        try (var in = CobaltWhatsAppGatewayTest.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static OutgoingMedia media(String type) {
        return new OutgoingMedia("5561999999999@s.whatsapp.net", type, "application/octet-stream", "x",
                "legenda", "a.pdf", null);
    }
}

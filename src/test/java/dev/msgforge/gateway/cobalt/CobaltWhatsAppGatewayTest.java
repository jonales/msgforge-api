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
import it.auties.whatsapp.model.message.standard.AudioMessage;
import it.auties.whatsapp.model.message.standard.DocumentMessage;
import it.auties.whatsapp.model.message.standard.ImageMessage;
import it.auties.whatsapp.model.message.standard.VideoOrGifMessage;
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

    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final InMemorySessionStore sessions = new InMemorySessionStore();
    private HttpServer server;

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
                new MsgforgeProperties.Cobalt("Msgforge", 3000, 3000, mediaMaxBytes, deadProxy()));
        return new CobaltWhatsAppGateway(events::add, sessions, new PrivateNetworkGuard(blockPrivateNetworks), properties);
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
                            "http://127.0.0.1:" + proxy.getLocalPort()));
            var gateway = new CobaltWhatsAppGateway(events::add, sessions, new PrivateNetworkGuard(false), properties);
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
        var bytes = "conteudo".getBytes();
        assertThat(CobaltWhatsAppGateway.buildMedia(media("image"), bytes)).isInstanceOf(ImageMessage.class);
        // Vídeo real (1 s, 64x64): com bytes inválidos e ffmpeg instalado, o ffprobe do Cobalt espera ~10 s
        assertThat(CobaltWhatsAppGateway.buildMedia(media("video"), resource("/media/tiny.mp4")))
                .isInstanceOf(VideoOrGifMessage.class);
        assertThat(CobaltWhatsAppGateway.buildMedia(media("audio"), bytes)).isInstanceOf(AudioMessage.class);
        var document = (DocumentMessage) CobaltWhatsAppGateway.buildMedia(media("document"), bytes);
        assertThat(document.fileName()).contains("a.pdf");
        assertThatThrownBy(() -> CobaltWhatsAppGateway.buildMedia(media("sticker"), bytes))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void buildsLocationContactsReactionPollAndVoiceMessages() {
        var jid = "5561999999999@s.whatsapp.net";

        var location = (it.auties.whatsapp.model.message.standard.LocationMessage) CobaltWhatsAppGateway.buildMessage(
                new dev.msgforge.gateway.WhatsAppGateway.Location(jid, -15.79, -47.88, "Esplanada", "Brasília", null), null);
        assertThat(location.latitude()).isEqualTo(-15.79);
        assertThat(location.longitude()).isEqualTo(-47.88);
        assertThat(location.name()).contains("Esplanada");

        var card = new dev.msgforge.gateway.WhatsAppGateway.ContactCard("Ana", "BEGIN:VCARD\nFN:Ana\nEND:VCARD");
        var one = CobaltWhatsAppGateway.buildMessage(
                new dev.msgforge.gateway.WhatsAppGateway.Contacts(jid, java.util.List.of(card), null), null);
        assertThat(one).isInstanceOf(it.auties.whatsapp.model.message.standard.ContactMessage.class);
        var many = CobaltWhatsAppGateway.buildMessage(
                new dev.msgforge.gateway.WhatsAppGateway.Contacts(jid, java.util.List.of(card, card), null), null);
        assertThat(many).isInstanceOf(it.auties.whatsapp.model.message.standard.ContactsMessage.class);

        var reaction = (it.auties.whatsapp.model.message.standard.ReactionMessage) CobaltWhatsAppGateway.buildMessage(
                new dev.msgforge.gateway.WhatsAppGateway.Reaction(jid, "3EB0ABC", true, "👍"), null);
        assertThat(reaction.content()).isEqualTo("👍");
        assertThat(reaction.key().id()).isEqualTo("3EB0ABC");
        assertThat(reaction.key().fromMe()).isTrue();
        assertThat(reaction.key().chatJid().toString()).isEqualTo(jid);

        var voice = (AudioMessage) CobaltWhatsAppGateway.buildMessage(
                new dev.msgforge.gateway.WhatsAppGateway.VoiceNote(jid, "x", null), "ogg".getBytes());
        assertThat(voice.voiceMessage()).isTrue();
        assertThat(voice.mimetype()).contains("audio/ogg; codecs=opus");
    }

    @Test
    void pollsKeepTheRequestedSelectableCountAndHashTheOptions() throws Exception {
        var single = CobaltWhatsAppGateway.poll(new dev.msgforge.gateway.WhatsAppGateway.Poll(
                "5561999999999@s.whatsapp.net", "Almoço?", 1, java.util.List.of("Sim", "Não"), null));

        assertThat(single.title()).isEqualTo("Almoço?");
        assertThat(single.selectableOptionsCount()).isEqualTo(1);   // o simpleBuilder do Cobalt forçaria 2
        assertThat(single.selectableOptions()).extracting(o -> o.name()).containsExactly("Sim", "Não");
        var sha = java.security.MessageDigest.getInstance("SHA-256");
        var hashOfSim = java.util.HexFormat.of().formatHex(sha.digest("Sim".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(single.getSelectableOption(hashOfSim)).isPresent();
        assertThat(single.encryptionKey()).isPresent();
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

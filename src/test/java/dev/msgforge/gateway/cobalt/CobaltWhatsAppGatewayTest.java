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

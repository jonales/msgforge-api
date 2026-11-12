package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.gateway.WhatsAppGateway;
import dev.msgforge.gateway.cobalt.CobaltWhatsAppGateway;
import dev.msgforge.support.IntegrationTest;
import dev.msgforge.support.WebhookReceiver.Received;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Aplicação inteira com MSGFORGE_GATEWAY=cobalt. O WhatsApp é inalcançável de propósito (proxy
 * numa porta fechada): valida fiação, boot e o caminho de falha sem depender da rede, também no CI.
 */
@TestPropertySource(properties = {
        "msgforge.gateway=cobalt",
        "msgforge.cobalt.proxy=http://127.0.0.1:9",
        "msgforge.cobalt.session-dir=${java.io.tmpdir}/msgforge-it-cobalt",
        "msgforge.cobalt.qr-wait-ms=3000",
})
class CobaltGatewayIT extends IntegrationTest {

    @Autowired
    WhatsAppGateway gateway;

    @Test
    void cobaltIsTheActiveGateway() {
        assertThat(gateway).isInstanceOf(CobaltWhatsAppGateway.class);
    }

    @Test
    void createWithQrCodeReportsConnectingThenCloseWhenWhatsAppIsUnreachable() {
        var name = uniqueName("cobalt");
        var created = post("/instance/create", """
                {"instanceName":"%s","qrcode":true,"webhook":{"url":"%s","byEvents":true}}
                """.formatted(name, webhooks.url("/" + name)));
        assertThat(created.status()).isEqualTo(201);

        var close = webhooks.await(r -> r.path().equals("/" + name + "/connection-update")
                && parse(r.body()).get("data").get("state").asString().equals("close"), Duration.ofSeconds(10));
        assertThat(parse(close.body()).get("data").get("statusReason").asInt()).isEqualTo(428);
        assertThat(webhooks.received()).filteredOn(r -> r.path().startsWith("/" + name + "/"))
                .extracting(Received::path).startsWith("/" + name + "/connection-update");
        assertThat(get("/instance/connectionState/" + name).body().get("instance").get("state").asString())
                .isEqualTo("close");
    }

    @Test
    void sendingToADisconnectedInstanceIsABadRequestAndLogoutWorks() {
        var name = uniqueName("cobalt");
        assertThat(post("/instance/create", "{\"instanceName\":\"" + name + "\"}").status()).isEqualTo(201);

        var sent = post("/message/sendText/" + name, """
                {"number":"5561999999999","text":"oi"}""");
        assertThat(sent.status()).isEqualTo(400);
        assertThat(sent.body().get("response").get("message").get(0).asString()).contains("not connected");

        assertThat(delete("/instance/logout/" + name).status()).isEqualTo(200);
        assertThat(delete("/instance/delete/" + name).status()).isEqualTo(200);
    }
}

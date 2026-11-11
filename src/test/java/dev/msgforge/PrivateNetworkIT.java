package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Com WEBHOOK_BLOCK_PRIVATE_NETWORKS=true, webhooks de instância não podem apontar para a rede interna. */
@TestPropertySource(properties = "msgforge.webhook.block-private-networks=true")
class PrivateNetworkIT extends IntegrationTest {

    @Test
    void internalWebhookUrlsAreRefused() {
        var name = uniqueName("ssrf");
        assertThat(post("/instance/create", "{\"instanceName\":\"" + name + "\"}").status()).isEqualTo(201);

        var metadata = post("/webhook/set/" + name, """
                {"webhook":{"url":"http://169.254.169.254/latest/meta-data"}}""");
        assertThat(metadata.status()).isEqualTo(400);
        assertThat(metadata.body().get("response").get("message").get(0).asString()).contains("not allowed");

        assertThat(post("/webhook/set/" + name, """
                {"webhook":{"url":"%s"}}""".formatted(webhooks.url("/x")))).extracting(Response::status).isEqualTo(400);
        assertThat(post("/instance/create", """
                {"instanceName":"%s","webhook":{"url":"http://localhost/x"}}""".formatted(uniqueName("ssrf"))))
                .extracting(Response::status).isEqualTo(400);

        assertThat(post("/webhook/set/" + name, """
                {"webhook":{"url":"https://8.8.8.8/hook"}}""").status()).as("IP público").isEqualTo(201);
    }
}

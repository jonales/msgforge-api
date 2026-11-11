package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.support.IntegrationTest;
import dev.msgforge.support.WebhookReceiver.Received;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Fluxo da Evolution de ponta a ponta: HTTP → serviço → banco → evento → webhook. */
class InstanceFlowIT extends IntegrationTest {

    private String createWithWebhook(String name, boolean qrcode, String extraWebhookJson) {
        var body = """
                {"instanceName":"%s","qrcode":%s,
                 "webhook":{"url":"%s","byEvents":true%s}}
                """.formatted(name, qrcode, webhooks.url("/hook"), extraWebhookJson);
        var response = post("/instance/create", body);
        assertThat(response.status()).isEqualTo(201);
        return response.body().get("hash").asString();
    }

    private void assertEvolutionError(Response response, int status) {
        assertThat(response.status()).isEqualTo(status);
        assertThat(response.body().get("status").asInt()).isEqualTo(status);
        assertThat(response.body().get("error").asString()).isNotBlank();
        assertThat(response.body().get("response").get("message").isArray()).isTrue();
    }

    @Test
    void createWithQrCodeDeliversQrAndConnectionEventsAfterCommit() {
        var name = uniqueName("vendas");
        var token = createWithWebhook(name, true, "");

        // Regressão: antes estes dois eventos se perdiam (entrega lia o banco antes do commit)
        var qr = webhooks.await("/hook/qrcode-updated");
        var connection = webhooks.await("/hook/connection-update");

        var qrBody = parse(qr.body());
        assertThat(qrBody.get("event").asString()).isEqualTo("qrcode.updated");
        assertThat(qrBody.get("instance").asString()).isEqualTo(name);
        assertThat(qrBody.get("apikey").asString()).isEqualTo(token);
        assertThat(qrBody.get("data").get("qrcode").get("code").asString()).startsWith("stub-qr-");
        assertThat(qrBody.has("date_time")).isTrue();
        assertThat(qrBody.has("server_url")).isTrue();
        assertThat(parse(connection.body()).get("data").get("state").asString()).isEqualTo("connecting");
    }

    @Test
    void webhookIsSentWithContentLengthNotChunked() {
        var name = uniqueName("loja");
        createWithWebhook(name, false, "");

        var sent = post("/message/sendText/" + name, """
                {"number":"+55 (61) 99999-9999","text":"oi"}""");
        assertThat(sent.status()).isEqualTo(201);
        assertThat(sent.body().get("key").get("remoteJid").asString()).isEqualTo("5561999999999@s.whatsapp.net");

        Received hook = webhooks.await("/hook/send-message");
        assertThat(hook.header("Content-Length")).isNotNull();
        assertThat(hook.header("Transfer-Encoding")).isNull();
        assertThat(hook.header("Content-Type")).startsWith("application/json");
        assertThat(parse(hook.body()).get("data").get("key").get("id").asString())
                .isEqualTo(sent.body().get("key").get("id").asString());
    }

    @Test
    void webhookIsRetriedOnServerError() {
        var name = uniqueName("retry");
        createWithWebhook(name, false, "");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        webhooks.respondWith(path -> calls.incrementAndGet() < 3 ? 503 : 200);

        post("/message/sendText/" + name, """
                {"number":"5561999999999","text":"oi"}""");

        webhooks.await(r -> webhooks.received().size() >= 3, Duration.ofSeconds(5));
        assertThat(webhooks.received()).hasSize(3).allMatch(r -> r.path().equals("/hook/send-message"));
    }

    @Test
    void eventsFilterAndCaseAreNormalized() {
        var name = uniqueName("filtro");
        createWithWebhook(name, false, ",\"events\":[\"send_message\"]");

        var found = get("/webhook/find/" + name);
        assertThat(found.body().get("events").get(0).asString()).isEqualTo("SEND_MESSAGE");

        get("/instance/connect/" + name);              // qrcode.updated + connection.update: filtrados
        post("/message/sendText/" + name, """
                {"number":"5561999999999","text":"oi"}""");
        webhooks.await("/hook/send-message");
        assertThat(webhooks.received()).extracting(Received::path).containsOnly("/hook/send-message");
    }

    @Test
    void deleteStillDeliversTheCloseEvent() {
        var name = uniqueName("apagar");
        createWithWebhook(name, true, "");
        webhooks.await("/hook/connection-update");
        webhooks.clear();

        assertThat(delete("/instance/delete/" + name).status()).isEqualTo(200);

        // Regressão: a entrega usa o snapshot, então funciona mesmo com a instância já apagada
        var close = webhooks.await("/hook/connection-update");
        assertThat(parse(close.body()).get("data").get("state").asString()).isEqualTo("close");
        assertEvolutionError(get("/instance/fetchInstances?instanceName=" + name), 404);
    }

    @Test
    void invalidInputsAnswerInTheEvolutionErrorFormat() {
        var name = uniqueName("erros");
        createWithWebhook(name, false, "");

        assertEvolutionError(post("/message/sendText/" + name, """
                {"number":"abc","text":"oi"}"""), 400);
        assertEvolutionError(post("/message/sendText/" + name, "{bad"), 400);
        assertEvolutionError(post("/message/sendText/" + name, """
                {"number":"5561999999999"}"""), 400);
        assertEvolutionError(post("/message/sendText/nao-existe", """
                {"number":"5561999999999","text":"oi"}"""), 404);
        assertEvolutionError(post("/webhook/set/" + name, """
                {"webhook":{"url":"http://x.test","events":["FOO"]}}"""), 400);
        assertEvolutionError(post("/webhook/set/" + name, """
                {"webhook":{"url":"ftp://x.test"}}"""), 400);
        assertEvolutionError(get("/rota/que/nao/existe"), 404);
        assertEvolutionError(call("PUT", "/instance/create", API_KEY, "{}"), 405);
    }

    @Test
    void instanceTokenOnlySeesItsOwnInstance() {
        var a = uniqueName("a");
        var b = uniqueName("b");
        assertThat(post("/instance/create", """
                {"instanceName":"%s","token":"TOKEN-A-%s"}""".formatted(a, a)).status()).isEqualTo(201);
        assertThat(post("/instance/create", """
                {"instanceName":"%s"}""".formatted(b)).status()).isEqualTo(201);
        var tokenA = "TOKEN-A-" + a;

        var own = call("GET", "/instance/fetchInstances", tokenA, null);
        assertThat(own.status()).isEqualTo(200);
        assertThat(own.body().size()).isEqualTo(1);
        assertThat(own.body().get(0).get("name").asString()).isEqualTo(a);

        assertThat(call("GET", "/instance/fetchInstances?instanceName=" + b, tokenA, null).body().size()).isZero();
        assertThat(call("GET", "/instance/connectionState/" + b, tokenA, null).status()).isEqualTo(401);
        assertThat(call("GET", "/instance/connectionState/" + a, tokenA, null).status()).isEqualTo(200);
        assertThat(call("GET", "/instance/fetchInstances", "token-errado", null).status()).isEqualTo(401);
        assertThat(get("/instance/fetchInstances").body().size()).isEqualTo(2);
    }

    @Test
    void tokensMustBeUnique() {
        var a = uniqueName("a");
        post("/instance/create", """
                {"instanceName":"%s","token":"MESMO-%s"}""".formatted(a, a));

        assertEvolutionError(post("/instance/create", """
                {"instanceName":"%s","token":"MESMO-%s"}""".formatted(uniqueName("b"), a)), 400);
        assertEvolutionError(post("/instance/create", """
                {"instanceName":"%s","token":"%s"}""".formatted(uniqueName("c"), API_KEY)), 400);
    }
}

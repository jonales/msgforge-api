package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.support.IntegrationTest;
import dev.msgforge.support.WebhookReceiver.Received;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Fluxo da Evolution de ponta a ponta: HTTP → serviço → banco → evento → webhook. */
class InstanceFlowIT extends IntegrationTest {

    /**
     * Cada teste usa o nome da instância como caminho do webhook ("/{nome}/{evento}") e só olha
     * os próprios webhooks: entregas atrasadas (retry) de outro teste não interferem.
     */
    private String createWithWebhook(String name, boolean qrcode, String extraWebhookJson) {
        var body = """
                {"instanceName":"%s","qrcode":%s,
                 "webhook":{"url":"%s","byEvents":true%s}}
                """.formatted(name, qrcode, webhooks.url("/" + name), extraWebhookJson);
        var response = post("/instance/create", body);
        assertThat(response.status()).isEqualTo(201);
        return response.body().get("hash").asString();
    }

    private static String hook(String name, String suffix) {
        return "/" + name + suffix;
    }

    private static java.util.List<Received> receivedFor(String name) {
        return webhooks.received().stream().filter(r -> r.path().startsWith("/" + name + "/")).toList();
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
        var qr = webhooks.await(hook(name, "/qrcode-updated"));
        var connection = webhooks.await(hook(name, "/connection-update"));

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

        Received received = webhooks.await(hook(name, "/send-message"));
        assertThat(received.header("Content-Length")).isNotNull();
        assertThat(received.header("Transfer-Encoding")).isNull();
        assertThat(received.header("Content-Type")).startsWith("application/json");
        assertThat(parse(received.body()).get("data").get("key").get("id").asString())
                .isEqualTo(sent.body().get("key").get("id").asString());
    }

    @Test
    void webhookIsRetriedOnServerError() {
        var name = uniqueName("retry");
        createWithWebhook(name, false, "");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        webhooks.respondWith(path -> path.startsWith("/" + name + "/") && calls.incrementAndGet() < 3 ? 503 : 200);

        post("/message/sendText/" + name, """
                {"number":"5561999999999","text":"oi"}""");

        webhooks.await(r -> receivedFor(name).size() >= 3, Duration.ofSeconds(5));
        assertThat(receivedFor(name)).hasSize(3).allMatch(r -> r.path().equals(hook(name, "/send-message")));
    }

    @Test
    void eventsOfAnInstanceArriveInOrderEvenWhenOneIsRetried() {
        var name = uniqueName("ordem");
        var qrFailures = new java.util.concurrent.atomic.AtomicInteger();
        // O qrcode.updated falha duas vezes; o connection.update (publicado depois) tem de esperar
        webhooks.respondWith(path -> path.equals(hook(name, "/qrcode-updated"))
                && qrFailures.incrementAndGet() <= 2 ? 503 : 200);

        createWithWebhook(name, true, "");

        webhooks.await(r -> receivedFor(name).size() >= 4, Duration.ofSeconds(5));
        assertThat(receivedFor(name)).extracting(Received::path).containsExactly(
                hook(name, "/qrcode-updated"), hook(name, "/qrcode-updated"),
                hook(name, "/qrcode-updated"), hook(name, "/connection-update"));
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
        webhooks.await(hook(name, "/send-message"));
        assertThat(receivedFor(name)).extracting(Received::path).containsOnly(hook(name, "/send-message"));
    }

    @Test
    void deleteStillDeliversTheCloseEvent() {
        var name = uniqueName("apagar");
        createWithWebhook(name, true, "");
        webhooks.await(hook(name, "/connection-update"));

        assertThat(delete("/instance/delete/" + name).status()).isEqualTo(200);

        // Regressão: a entrega usa o snapshot, então funciona mesmo com a instância já apagada
        var close = webhooks.await(r -> r.path().equals(hook(name, "/connection-update"))
                && parse(r.body()).get("data").get("state").asString().equals("close"), Duration.ofSeconds(5));
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
    void concurrentCreatesWithTheSameNameNeverAnswer500() throws Exception {
        var name = uniqueName("corrida");
        var body = "{\"instanceName\":\"%s\"}".formatted(name);
        var statuses = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            for (int i = 0; i < 10; i++) {
                pool.submit(() -> {
                    start.await();
                    statuses.add(post("/instance/create", body).status());
                    return null;
                });
            }
            start.countDown();
        }
        assertThat(statuses).hasSize(10).containsOnly(201, 400);
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
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

package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.instance.HistorySync;
import dev.msgforge.instance.InstanceService;
import dev.msgforge.security.SecretCipher;
import dev.msgforge.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** POST /settings/set, GET /settings/find, POST /proxy/set e GET /proxy/find: depois do create. */
class InstanceSettingsIT extends IntegrationTest {

    private static final String ALL_ON = """
            {"rejectCall":true,"msgCall":"Não atendo ligações","groupsIgnore":true,"alwaysOnline":true,
             "readMessages":true,"readStatus":true,"syncFullHistory":true,"wavoipToken":"ignorado"}""";

    @Autowired
    private InstanceService instances;

    @Autowired
    private SecretCipher secrets;

    private String create() {
        var name = uniqueName("ajustes");
        assertThat(post("/instance/create", "{\"instanceName\":\"" + name + "\"}").status()).isEqualTo(201);
        return name;
    }

    @Test
    void findReturnsTheDefaultsAndSetReplacesThem() {
        var name = create();
        var found = get("/settings/find/" + name);
        assertThat(found.status()).isEqualTo(200);
        assertThat(found.body().get("rejectCall").asBoolean()).isFalse();
        assertThat(found.body().get("syncFullHistory").asBoolean()).isFalse();
        assertThat(found.body().get("msgCall").asString()).isEmpty();

        var set = post("/settings/set/" + name, ALL_ON);
        assertThat(set.status()).isEqualTo(201);
        assertThat(set.body().get("settings").get("instanceName").asString()).isEqualTo(name);
        var echoed = set.body().get("settings").get("settings");
        assertThat(echoed.get("readStatus").asBoolean()).isTrue();
        assertThat(echoed.has("wavoipToken")).isFalse();

        var after = get("/settings/find/" + name).body();
        assertThat(after.get("rejectCall").asBoolean()).isTrue();
        assertThat(after.get("msgCall").asString()).isEqualTo("Não atendo ligações");
        assertThat(after.get("groupsIgnore").asBoolean()).isTrue();
        assertThat(after.get("alwaysOnline").asBoolean()).isTrue();
        assertThat(after.get("readMessages").asBoolean()).isTrue();
        assertThat(after.get("syncFullHistory").asBoolean()).isTrue();
        assertThat(instances.get(name).getHistorySync()).isEqualTo(HistorySync.FULL);
        assertThat(instances.get(name).getSettings().groupsIgnore()).isTrue();

        // false desfaz o "completo"; um "recente" escolhido no Manager não é tocado
        post("/settings/set/" + name, ALL_ON.replace("\"syncFullHistory\":true", "\"syncFullHistory\":false"));
        assertThat(instances.get(name).getHistorySync()).isEqualTo(HistorySync.NONE);
        instances.setHistorySync(name, HistorySync.RECENT);
        post("/settings/set/" + name, ALL_ON.replace("\"syncFullHistory\":true", "\"syncFullHistory\":false"));
        assertThat(instances.get(name).getHistorySync()).isEqualTo(HistorySync.RECENT);
    }

    @Test
    void settingsValidationUsesTheContractErrorFormat() {
        var name = create();
        var missing = post("/settings/set/" + name, "{\"rejectCall\":true}");
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.body().get("status").asInt()).isEqualTo(400);
        assertThat(missing.body().get("error").asString()).isEqualTo("Bad Request");
        assertThat(missing.body().get("response").get("message").toString()).contains("groupsIgnore");

        var tooLong = post("/settings/set/" + name, ALL_ON.replace("Não atendo ligações", "x".repeat(4097)));
        assertThat(tooLong.status()).isEqualTo(400);
        assertThat(tooLong.body().get("response").get("message").toString()).contains("msgCall");
        assertThat(instances.get(name).getSettings().rejectCall()).isFalse();

        assertThat(get("/settings/find/nao-existe-" + name).status()).isEqualTo(404);
        assertThat(post("/settings/set/nao-existe-" + name, ALL_ON).status()).isEqualTo(404);
    }

    @Test
    void proxySetStoresThePasswordEncryptedAndFindNeverReturnsIt() {
        var name = create();
        var none = get("/proxy/find/" + name);
        assertThat(none.status()).isEqualTo(200);
        assertThat(none.body().get("enabled").asBoolean()).isFalse();
        assertThat(none.body().get("host").asString()).isEmpty();

        var set = post("/proxy/set/" + name, """
                {"enabled":true,"host":"proxy.example.com","port":"3128","protocol":"HTTP",
                 "username":"cliente","password":"senha-do-proxy"}""");
        assertThat(set.status()).isEqualTo(201);
        assertThat(set.body().toString()).doesNotContain("senha-do-proxy").doesNotContain("password");
        assertThat(set.body().get("proxy").get("instanceName").asString()).isEqualTo(name);

        var found = get("/proxy/find/" + name);
        assertThat(found.body().get("enabled").asBoolean()).isTrue();
        assertThat(found.body().get("host").asString()).isEqualTo("proxy.example.com");
        assertThat(found.body().get("port").asString()).isEqualTo("3128");
        assertThat(found.body().get("protocol").asString()).isEqualTo("http");
        assertThat(found.body().get("username").asString()).isEqualTo("cliente");
        assertThat(found.body().has("password")).isFalse();
        var encrypted = instances.get(name).getProxy().orElseThrow().passwordEncrypted();
        assertThat(found.body().toString()).doesNotContain("senha-do-proxy").doesNotContain(encrypted);
        assertThat(secrets.decrypt(instances.get(name).getId(), encrypted)).isEqualTo("senha-do-proxy");

        // A API grava o que veio: sem senha, sem senha
        post("/proxy/set/" + name, """
                {"enabled":true,"host":"10.0.0.5","port":1080,"protocol":"socks","username":"cliente"}""");
        assertThat(instances.get(name).getProxy()).hasValueSatisfying(proxy -> {
            assertThat(proxy.protocol()).isEqualTo("socks5");
            assertThat(proxy.port()).isEqualTo(1080);
            assertThat(proxy.hasPassword()).isFalse();
        });

        var off = post("/proxy/set/" + name, """
                {"enabled":false,"host":"","port":"","protocol":""}""");
        assertThat(off.status()).isEqualTo(201);
        assertThat(instances.get(name).getProxy()).isEmpty();
        assertThat(get("/proxy/find/" + name).body().get("enabled").asBoolean()).isFalse();
    }

    @Test
    void invalidProxyIsABadRequestAndKeepsTheCurrentOne() {
        var name = create();
        post("/proxy/set/" + name, """
                {"enabled":true,"host":"proxy","port":"3128","protocol":"http"}""");
        var cases = Map.of(
                "{\"host\":\"proxy\",\"port\":\"3128\",\"protocol\":\"http\"}", "enabled",
                "{\"enabled\":true}", "host, port and protocol are required",
                "{\"enabled\":true,\"host\":\"proxy\",\"port\":\"3128\"}", "together",
                "{\"enabled\":true,\"host\":\"proxy\",\"port\":\"3128\",\"protocol\":\"ftp\"}", "protocol must be",
                "{\"enabled\":true,\"host\":\"proxy\",\"port\":\"99999\",\"protocol\":\"http\"}", "port must be",
                "{\"enabled\":true,\"host\":\"user@proxy\",\"port\":\"80\",\"protocol\":\"http\"}", "host is invalid",
                "{\"enabled\":true,\"host\":\"proxy\",\"port\":\"80\",\"protocol\":\"http\",\"password\":\"x\"}",
                "password needs username");
        cases.forEach((body, expected) -> {
            var response = post("/proxy/set/" + name, body);
            assertThat(response.status()).as(body).isEqualTo(400);
            assertThat(response.body().get("error").asString()).as(body).isEqualTo("Bad Request");
            assertThat(response.body().get("response").get("message").toString()).as(body).contains(expected)
                    .doesNotContain("\"x\"");
            assertThat(instances.get(name).getProxy()).as(body)
                    .hasValueSatisfying(proxy -> assertThat(proxy.port()).isEqualTo(3128));
        });
    }

    @Test
    void theInstanceTokenOnlyReachesItsOwnSettings() {
        var name = create();
        var other = create();
        var token = instances.get(name).getToken();
        assertThat(call("GET", "/settings/find/" + name, token, null).status()).isEqualTo(200);
        assertThat(call("POST", "/proxy/set/" + name, token, "{\"enabled\":false}").status()).isEqualTo(201);
        assertThat(call("GET", "/proxy/find/" + other, token, null).status()).isEqualTo(401);
    }
}

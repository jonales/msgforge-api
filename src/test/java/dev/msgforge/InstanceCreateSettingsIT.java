package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.instance.HistorySync;
import dev.msgforge.instance.InstanceService;
import dev.msgforge.security.SecretCipher;
import dev.msgforge.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Campos de configuração e de proxy do POST /instance/create: guardados, nunca descartados em silêncio. */
class InstanceCreateSettingsIT extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InstanceService instances;

    @Autowired
    private SecretCipher secrets;

    private Map<String, Object> row(String name) {
        return jdbc.queryForMap("SELECT * FROM instance WHERE name = ?", name);
    }

    @Test
    void settingsAndProxyAreStoredWithThePasswordEncrypted() {
        var name = uniqueName("config");
        var response = post("/instance/create", """
                {"instanceName":"%s","rejectCall":true,"msgCall":"Não atendo ligações",
                 "groupsIgnore":true,"alwaysOnline":true,"readMessages":true,"readStatus":true,
                 "syncFullHistory":true,
                 "proxyHost":"proxy.example.com","proxyPort":"3128","proxyProtocol":"HTTP",
                 "proxyUsername":"cliente","proxyPassword":"senha-do-proxy"}
                """.formatted(name));

        assertThat(response.status()).isEqualTo(201);
        assertThat(response.body().toString()).doesNotContain("senha-do-proxy");
        var row = row(name);
        assertThat(row).containsEntry("reject_call", true).containsEntry("msg_call", "Não atendo ligações")
                .containsEntry("groups_ignore", true).containsEntry("always_online", true)
                .containsEntry("read_messages", true).containsEntry("read_status", true)
                .containsEntry("history_sync", "FULL")
                .containsEntry("proxy_protocol", "http").containsEntry("proxy_host", "proxy.example.com")
                .containsEntry("proxy_port", 3128).containsEntry("proxy_username", "cliente");
        var encrypted = (String) row.get("proxy_password_encrypted");
        assertThat(encrypted).startsWith("v1:").doesNotContain("senha-do-proxy");

        var instance = instances.get(name);
        assertThat(secrets.decrypt(instance.getId(), encrypted)).isEqualTo("senha-do-proxy");
        assertThat(instance.getSettings().rejectCall()).isTrue();
        assertThat(instance.getProxy()).hasValueSatisfying(proxy -> assertThat(proxy.port()).isEqualTo(3128));

        var fetched = get("/instance/fetchInstances?instanceName=" + name);
        assertThat(fetched.body().toString()).doesNotContain("senha-do-proxy").doesNotContain(encrypted);
    }

    @Test
    void withoutTheFieldsTheContractDefaultsApply() {
        var name = uniqueName("padrao");
        assertThat(post("/instance/create", """
                {"instanceName":"%s","syncFullHistory":false}""".formatted(name)).status()).isEqualTo(201);

        assertThat(row(name)).containsEntry("reject_call", false).containsEntry("groups_ignore", false)
                .containsEntry("always_online", false).containsEntry("read_messages", false)
                .containsEntry("read_status", false).containsEntry("history_sync", HistorySync.NONE.name())
                .containsEntry("proxy_host", null).containsEntry("msg_call", null);
    }

    @Test
    void numericPortAndSocksAliasAreAccepted() {
        var name = uniqueName("socks");
        assertThat(post("/instance/create", """
                {"instanceName":"%s","proxyHost":"10.0.0.5","proxyPort":1080,"proxyProtocol":"socks"}
                """.formatted(name)).status()).isEqualTo(201);

        assertThat(row(name)).containsEntry("proxy_protocol", "socks5").containsEntry("proxy_port", 1080)
                .containsEntry("proxy_password_encrypted", null);
    }

    @Test
    void invalidProxyIsABadRequestAndCreatesNothing() {
        var cases = Map.of(
                "{\"proxyHost\":\"proxy\",\"proxyPort\":\"3128\"}", "together",
                "{\"proxyHost\":\"proxy\",\"proxyPort\":\"3128\",\"proxyProtocol\":\"ftp\"}", "proxyProtocol",
                "{\"proxyHost\":\"proxy\",\"proxyPort\":\"99999\",\"proxyProtocol\":\"http\"}", "proxyPort",
                "{\"proxyHost\":\"user@proxy\",\"proxyPort\":\"80\",\"proxyProtocol\":\"http\"}", "proxyHost",
                "{\"proxyHost\":\"proxy\",\"proxyPort\":\"80\",\"proxyProtocol\":\"http\",\"proxyPassword\":\"x\"}",
                "proxyUsername",
                "{\"proxyUsername\":\"cliente\"}", "proxyHost");
        cases.forEach((fields, expected) -> {
            var name = uniqueName("ruim");
            var body = "{\"instanceName\":\"" + name + "\"," + fields.substring(1);
            var response = post("/instance/create", body);
            assertThat(response.status()).as(fields).isEqualTo(400);
            assertThat(response.body().get("response").get("message").toString()).as(fields).contains(expected);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM instance WHERE name = ?", Long.class, name))
                    .as(fields).isZero();
        });
    }
}

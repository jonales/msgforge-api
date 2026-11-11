package dev.msgforge;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.gateway.session.SessionStore;
import dev.msgforge.instance.InstanceRepository;
import dev.msgforge.instance.SessionRestorer;
import dev.msgforge.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SessionRestoreIT extends IntegrationTest {

    @Autowired
    SessionStore sessions;

    @Autowired
    SessionRestorer restorer;

    @Autowired
    InstanceRepository instances;

    @Test
    void instancesWithStoredSessionReconnectAndLogoutDropsTheSession() {
        var withSession = uniqueName("com-sessao");
        var withoutSession = uniqueName("sem-sessao");
        post("/instance/create", "{\"instanceName\":\"" + withSession + "\"}");
        post("/instance/create", "{\"instanceName\":\"" + withoutSession + "\"}");
        var id = instances.findByName(withSession).orElseThrow().getId();
        sessions.save(id, "credenciais".getBytes(StandardCharsets.UTF_8));
        sessions.save(id, "credenciais-v2".getBytes(StandardCharsets.UTF_8));   // substitui, não duplica

        assertThat(restorer.restoreAll()).isEqualTo(1);
        assertThat(state(withSession)).isEqualTo("connecting");
        assertThat(state(withoutSession)).isEqualTo("close");
        assertThat(new String(sessions.load(id).orElseThrow(), StandardCharsets.UTF_8)).isEqualTo("credenciais-v2");

        assertThat(delete("/instance/logout/" + withSession).status()).isEqualTo(200);
        assertThat(sessions.exists(id)).isFalse();
        assertThat(state(withSession)).isEqualTo("close");
    }

    @Test
    void deletingTheInstanceDeletesItsSession() {
        var name = uniqueName("cascata");
        post("/instance/create", "{\"instanceName\":\"" + name + "\"}");
        var id = instances.findByName(name).orElseThrow().getId();
        sessions.save(id, new byte[] {1, 2, 3});

        delete("/instance/delete/" + name);

        assertThat(sessions.exists(id)).isFalse();
    }

    private String state(String name) {
        return get("/instance/connectionState/" + name).body().get("instance").get("state").asString();
    }
}

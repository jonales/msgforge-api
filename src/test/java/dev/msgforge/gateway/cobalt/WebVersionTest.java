package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;

import it.auties.whatsapp.model.signal.auth.Version;
import it.auties.whatsapp.util.AppMetadata;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebVersionTest {

    @Test
    void readsTheRevisionFromTheServiceWorker() {
        // Trecho no formato do sw.js atual: JSON escapado dentro de uma string
        var sw = "self.__swData=JSON.parse(\"{\\\"dynamic_data\\\":{\\\"SiteData\\\":{\\\"client_revision\\\":1054172749,"
                + "\\\"server_revision\\\":1054172749}}}\");";

        assertThat(WebVersion.parse(sw)).hasValue("2.3000.1054172749");
        assertThat(WebVersion.parse("{\"client_revision\": 1022032575}")).hasValue("2.3000.1022032575");
        assertThat(WebVersion.parse("<html>sem revisão</html>")).isEmpty();
    }

    @Test
    void overrideIsInstalledInCobaltWithoutFetching() throws Exception {
        new WebVersion("2.3000.1054172749", null, Clock.systemUTC()).apply();

        assertThat(cobaltWebVersion()).isEqualTo(Version.of("2.3000.1054172749"));
    }

    @Test
    void fetchFailureKeepsCobaltVersionAndRetriesLater() throws Exception {
        new WebVersion("2.3000.7", null, Clock.systemUTC()).apply();
        String deadProxy;
        try (var socket = new ServerSocket(0)) {
            deadProxy = "http://127.0.0.1:" + socket.getLocalPort();   // fechada ao sair do try
        }
        var clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);

        new WebVersion(null, deadProxy, clock).apply();

        assertThat(cobaltWebVersion()).isEqualTo(Version.of("2.3000.7"));
    }

    private static Object cobaltWebVersion() throws Exception {
        var field = AppMetadata.class.getDeclaredField("webVersion");
        field.setAccessible(true);
        return field.get(null);
    }
}

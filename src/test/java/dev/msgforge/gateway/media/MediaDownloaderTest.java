// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.gateway.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import dev.msgforge.common.ApiExceptionHandler.BadRequestException;
import dev.msgforge.security.PrivateNetworkGuard;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MediaDownloaderTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /** O caminho de produção, com a proteção dos webhooks desligada (o padrão). */
    private final MediaDownloader production = MediaDownloader.guarded(new PrivateNetworkGuard(false), TIMEOUT);

    private HttpServer server;
    private final List<String> requested = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/a.png", "http://localhost/a.png", "http://10.1.2.3/a.png",
            "http://169.254.169.254/latest/meta-data/", "http://[::1]/a.png", "https://192.168.0.1/a.png",
            "http://[fd00::1]/a.png", "http://[::ffff:10.0.0.1]/a.png", "http://0.0.0.0/a.png"})
    void privateLoopbackLinkLocalAndMetadataAreAlwaysRefused(String url) {
        assertThatThrownBy(() -> production.download(url, 1024))
                .as(url).isInstanceOf(BadRequestException.class).hasMessageContaining("not allowed");
    }

    @Test
    void onlyHttpAndHttpsAreDownloaded() {
        assertThatThrownBy(() -> production.download("file:///etc/passwd", 1024))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("only http(s)");
    }

    @Test
    void aRedirectToAnInternalAddressIsRefusedBeforeConnecting() throws Exception {
        var origin = serve(exchange -> {
            exchange.getResponseHeaders().add("Location", "http://169.254.169.254/latest/meta-data/");
            exchange.sendResponseHeaders(302, -1);
        });
        // O servidor de teste é "público" para este downloader; o destino do redirect, não
        var downloader = new MediaDownloader(allowOnly(origin), TIMEOUT);

        assertThatThrownBy(() -> downloader.download(origin + "/a.png", 1024))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not allowed").hasMessageContaining("169.254.169.254");
        assertThat(requested).containsExactly("/a.png");
    }

    @Test
    void redirectsAreFollowedOneByOneAndEachHopIsChecked() throws Exception {
        var origin = serve(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/a.png")) {
                exchange.getResponseHeaders().add("Location", "/b.png");   // relativo
                exchange.sendResponseHeaders(301, -1);
            } else {
                var body = new byte[] {7, 8, 9};
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        var checked = new CopyOnWriteArrayList<String>();
        var downloader = new MediaDownloader(url -> {
            checked.add(url);
            return Optional.empty();
        }, TIMEOUT);

        assertThat(downloader.download(origin + "/a.png", 1024)).containsExactly(7, 8, 9);
        assertThat(checked).containsExactly(origin + "/a.png", origin + "/b.png");
    }

    @Test
    void redirectLoopsStop() throws Exception {
        var origin = serve(exchange -> {
            exchange.getResponseHeaders().add("Location", "/a.png");
            exchange.sendResponseHeaders(302, -1);
        });
        var downloader = new MediaDownloader(url -> Optional.empty(), TIMEOUT);

        assertThatThrownBy(() -> downloader.download(origin + "/a.png", 1024))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("too many redirects");
        assertThat(requested).hasSize(MediaDownloader.MAX_REDIRECTS + 1);
    }

    @Test
    void networkErrorsDoNotLeakTheExceptionMessage() throws Exception {
        String closed;
        try (var socket = new java.net.ServerSocket(0)) {
            closed = "http://127.0.0.1:" + socket.getLocalPort();
        }
        var downloader = new MediaDownloader(url -> Optional.empty(), TIMEOUT);

        assertThatThrownBy(() -> downloader.download(closed + "/a.png", 1024))
                .isInstanceOf(BadRequestException.class).hasMessage("Could not download media");
    }

    @Test
    void readsAtMostTheLimit() throws Exception {
        var origin = serve(exchange -> {
            var body = new byte[100];
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        });
        var downloader = new MediaDownloader(url -> Optional.empty(), TIMEOUT);

        assertThat(downloader.download(origin + "/a.png", 10)).hasSize(10);
    }

    private java.util.function.Function<String, Optional<String>> allowOnly(String origin) {
        var guard = new PrivateNetworkGuard(true);
        return url -> url.startsWith(origin + "/") ? Optional.empty() : guard.inspect(url);
    }

    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException;
    }

    private String serve(Handler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requested.add(exchange.getRequestURI().getPath());
            handler.handle(exchange);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}

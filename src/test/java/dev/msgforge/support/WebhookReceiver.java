package dev.msgforge.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Servidor HTTP local que grava os webhooks recebidos, para os testes conferirem. */
public final class WebhookReceiver implements AutoCloseable {

    public record Received(String path, Map<String, List<String>> headers, String body) {

        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().getFirst())
                    .findFirst()
                    .orElse(null);
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile ToIntFunction<String> status = path -> 200;

    public WebhookReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var path = exchange.getRequestURI().getPath();
            received.add(new Received(path, Map.copyOf(exchange.getRequestHeaders()), body));
            exchange.sendResponseHeaders(status.applyAsInt(path), -1);
            exchange.close();
        });
        server.start();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Status HTTP devolvido por path (padrão 200). */
    public void respondWith(ToIntFunction<String> status) {
        this.status = status;
    }

    public List<Received> received() {
        return List.copyOf(received);
    }

    public void clear() {
        received.clear();
        status = path -> 200;
    }

    /** Espera até um webhook satisfazer o filtro; falha com a lista recebida se não chegar. */
    public Received await(Predicate<Received> filter, Duration timeout) {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (var r : received) {
                if (filter.test(r)) {
                    return r;
                }
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Webhook esperado não chegou em " + timeout + ". Recebidos: " + received);
    }

    public Received await(String path) {
        return await(r -> r.path().equals(path), Duration.ofSeconds(5));
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

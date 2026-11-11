package dev.msgforge.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Freio contra força bruta no login do Manager: no máximo {@value #MAX_FAILURES} falhas por IP
 * em {@link #WINDOW}. Depois disso, até a falha mais antiga sair da janela, nem a chave certa
 * entra. Só em memória (zera no restart) e por IP de origem: atrás de um proxy reverso, todos
 * os clientes compartilham o IP do proxy (configure {@code server.forward-headers-strategy}).
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 10;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final ConcurrentHashMap<String, ArrayDeque<Instant>> failures = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean blocked(String ip) {
        var recent = failures.get(key(ip));
        if (recent == null) {
            return false;
        }
        synchronized (recent) {
            prune(recent);
            return recent.size() >= MAX_FAILURES;
        }
    }

    public void failed(String ip) {
        var recent = failures.computeIfAbsent(key(ip), k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent);
            recent.addLast(clock.instant());
        }
    }

    public void succeeded(String ip) {
        failures.remove(key(ip));
    }

    private void prune(ArrayDeque<Instant> recent) {
        var limit = clock.instant().minus(WINDOW);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(limit)) {
            recent.pollFirst();
        }
    }

    private static String key(String ip) {
        return ip == null ? "?" : ip;
    }
}

package dev.msgforge.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Janela deslizante de falhas por chave (o IP de origem): bloqueia quem acumula
 * {@code maxFailures} falhas em {@code window}, até a mais antiga sair da janela. Só em memória
 * (zera no restart). Usada pelo login do Manager e pela chave da API.
 */
public final class FailureWindow {

    /** Acima disso, entradas vazias são descartadas a cada falha nova (limita a memória). */
    static final int CLEANUP_THRESHOLD = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentHashMap<String, ArrayDeque<Instant>> failures = new ConcurrentHashMap<>();

    public FailureWindow(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    public boolean blocked(String key) {
        var recent = failures.get(normalize(key));
        if (recent == null) {
            return false;
        }
        synchronized (recent) {
            prune(recent);
            return recent.size() >= maxFailures;
        }
    }

    public void failed(String key) {
        var recent = failures.computeIfAbsent(normalize(key), k -> new ArrayDeque<>());
        synchronized (recent) {
            prune(recent);
            recent.addLast(clock.instant());
        }
        if (failures.size() > CLEANUP_THRESHOLD) {
            cleanup();
        }
    }

    public void succeeded(String key) {
        failures.remove(normalize(key));
    }

    /** Até quando a chave fica bloqueada (a falha mais antiga + janela); para o Retry-After. */
    public Duration retryAfter(String key) {
        var recent = failures.get(normalize(key));
        if (recent == null) {
            return Duration.ZERO;
        }
        synchronized (recent) {
            prune(recent);
            if (recent.size() < maxFailures) {
                return Duration.ZERO;
            }
            var wait = Duration.between(clock.instant(), recent.peekFirst().plus(window));
            return wait.isNegative() ? Duration.ZERO : wait;
        }
    }

    int trackedKeys() {
        return failures.size();
    }

    private void cleanup() {
        failures.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue());
                return entry.getValue().isEmpty();
            }
        });
    }

    private void prune(ArrayDeque<Instant> recent) {
        var limit = clock.instant().minus(window);
        while (!recent.isEmpty() && recent.peekFirst().isBefore(limit)) {
            recent.pollFirst();
        }
    }

    private static String normalize(String key) {
        return key == null ? "?" : key;
    }
}

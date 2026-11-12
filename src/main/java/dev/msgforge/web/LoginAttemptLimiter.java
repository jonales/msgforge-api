package dev.msgforge.web;

import dev.msgforge.security.FailureWindow;
import java.time.Clock;
import java.time.Duration;
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

    private final FailureWindow failures;

    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.failures = new FailureWindow(MAX_FAILURES, WINDOW, clock);
    }

    public boolean blocked(String ip) {
        return failures.blocked(ip);
    }

    public void failed(String ip) {
        failures.failed(ip);
    }

    public void succeeded(String ip) {
        failures.succeeded(ip);
    }
}

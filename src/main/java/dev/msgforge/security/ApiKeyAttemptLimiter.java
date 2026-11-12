package dev.msgforge.security;

import dev.msgforge.config.MsgforgeProperties;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Freio contra força bruta na chave da API: um IP que erra a chave
 * {@code AUTHENTICATION_MAX_FAILURES} vezes em {@link #WINDOW} recebe 429 em tudo, inclusive com
 * a chave certa (senão um 200 no meio dos 429 entregaria qual chave é a certa). Zero desliga.
 * Atrás de um proxy reverso, configure {@code server.forward-headers-strategy} para ver o IP real.
 */
@Component
public class ApiKeyAttemptLimiter {

    static final Duration WINDOW = Duration.ofMinutes(15);

    private final FailureWindow failures;
    private final boolean enabled;

    @Autowired
    public ApiKeyAttemptLimiter(MsgforgeProperties properties) {
        this(properties.authentication().maxFailures(), Clock.systemUTC());
    }

    ApiKeyAttemptLimiter(int maxFailures, Clock clock) {
        this.enabled = maxFailures > 0;
        this.failures = new FailureWindow(Math.max(maxFailures, 1), WINDOW, clock);
    }

    public boolean blocked(String ip) {
        return enabled && failures.blocked(ip);
    }

    public void failed(String ip) {
        if (enabled) {
            failures.failed(ip);
        }
    }

    /** Segundos até liberar (para o header Retry-After); no mínimo 1. */
    public long retryAfterSeconds(String ip) {
        return Math.max(1, (failures.retryAfter(ip).toMillis() + 999) / 1000);
    }
}

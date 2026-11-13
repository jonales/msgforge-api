package dev.msgforge.event.outbox;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retenção da outbox (OUTBOX_RETENTION_HOURS): a cada 10 minutos apaga, em lotes, as entregas
 * FEITO mais velhas que o prazo. As MORTO ficam, para o Manager mostrar e reenviar (ADR 0012).
 * Em várias réplicas, todas rodam a limpeza; apagar o que já foi apagado não faz nada.
 */
@Component
@ConditionalOnProperty(prefix = "msgforge.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRetention {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetention.class);
    static final int BATCH = 5_000;

    private final Outbox outbox;
    private final OutboxProperties properties;

    public OutboxRetention(Outbox outbox, OutboxProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT10M")
    void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Retenção da outbox falhou (tenta de novo em 10 min): {}", e.getMessage());
        }
    }

    /** Apaga as entregas feitas que passaram do prazo; devolve quantas saíram. */
    public long purge() {
        if (properties.retentionHours() <= 0) {
            return 0;
        }
        var age = Duration.ofHours(properties.retentionHours());
        long total = 0;
        int deleted;
        do {
            deleted = outbox.deleteDoneOlderThan(age, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("Retenção da outbox: {} entrega(s) com mais de {} h apagada(s)", total, properties.retentionHours());
        }
        return total;
    }
}

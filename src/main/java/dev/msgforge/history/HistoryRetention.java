package dev.msgforge.history;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Retenção do histórico (HISTORY_RETENTION_DAYS): de hora em hora, apaga as mensagens mais
 * velhas que o prazo, em lotes. Com 0 (padrão), não apaga nada. Em várias réplicas, todas rodam a
 * limpeza; não tem problema, porque apagar o que já foi apagado não faz nada.
 */
@Configuration
@EnableScheduling
public class HistoryRetention {

    private static final Logger log = LoggerFactory.getLogger(HistoryRetention.class);
    static final int BATCH = 5_000;

    private final MessageHistory history;
    private final HistoryProperties properties;
    private final Clock clock;

    @Autowired
    public HistoryRetention(MessageHistory history, HistoryProperties properties) {
        this(history, properties, Clock.systemUTC());
    }

    /** Com relógio explícito (testes). */
    public HistoryRetention(MessageHistory history, HistoryProperties properties, Clock clock) {
        this.history = history;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Retenção do histórico falhou (tenta de novo na próxima hora): {}", e.getMessage());
        }
    }

    /** Apaga o que passou do prazo; devolve quantas mensagens saíram. */
    public long purge() {
        if (properties.retentionDays() <= 0) {
            return 0;
        }
        var cutoff = clock.instant().minus(Duration.ofDays(properties.retentionDays())).getEpochSecond();
        long total = 0;
        int deleted;
        do {
            deleted = history.deleteOlderThan(cutoff, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("Retenção do histórico: {} mensagem(ns) com mais de {} dia(s) apagada(s)",
                    total, properties.retentionDays());
        }
        return total;
    }
}

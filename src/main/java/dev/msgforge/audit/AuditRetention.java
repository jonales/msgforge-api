// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.audit;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Retenção da auditoria (AUDIT_RETENTION_DAYS, padrão 180): de hora em hora, apaga em lotes o que
 * passou do prazo. O IP é dado pessoal (LGPD), por isso o padrão não é "para sempre" (ADR 0018).
 * Com 0, não apaga nada. Em várias réplicas, todas rodam; apagar o que já saiu não faz nada.
 */
@Configuration
@EnableScheduling
public class AuditRetention {

    private static final Logger log = LoggerFactory.getLogger(AuditRetention.class);
    static final int BATCH = 5_000;

    private final AuditLog audit;
    private final AuditProperties properties;
    private final Clock clock;

    @Autowired
    public AuditRetention(AuditLog audit, AuditProperties properties) {
        this(audit, properties, Clock.systemUTC());
    }

    /** Com relógio explícito (testes). */
    public AuditRetention(AuditLog audit, AuditProperties properties, Clock clock) {
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1H")
    void scheduled() {
        try {
            purge();
        } catch (RuntimeException e) {
            log.warn("Retenção da auditoria falhou (tenta de novo na próxima hora): {}", e.getMessage());
        }
    }

    /** Apaga o que passou do prazo; devolve quantos registros saíram. */
    public long purge() {
        if (properties.retentionDays() <= 0) {
            return 0;
        }
        var cutoff = clock.instant().minus(Duration.ofDays(properties.retentionDays()));
        long total = 0;
        int deleted;
        do {
            deleted = audit.deleteOlderThan(cutoff, BATCH);
            total += deleted;
        } while (deleted == BATCH);
        if (total > 0) {
            log.info("Retenção da auditoria: {} registro(s) com mais de {} dia(s) apagado(s)",
                    total, properties.retentionDays());
        }
        return total;
    }
}

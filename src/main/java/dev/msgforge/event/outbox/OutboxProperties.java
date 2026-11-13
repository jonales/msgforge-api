package dev.msgforge.event.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outbox das entregas de eventos (ADR 0012).
 *
 * @param enabled        grava as entregas na tabela {@code outbox} antes de entregar (OUTBOX_ENABLED);
 *                       false volta à fila em memória da ADR 0006
 * @param retentionHours apaga as entregas feitas mais velhas que isso (OUTBOX_RETENTION_HOURS);
 *                       0 guarda para sempre
 * @param pollIntervalMs varredura periódica do trabalhador, além do sinal de linha nova
 *                       (OUTBOX_POLL_INTERVAL_MS)
 * @param maxInFlight    entregas simultâneas deste nó (OUTBOX_MAX_IN_FLIGHT)
 * @param leaseSeconds   quanto uma entrega pode ficar EM_ENVIO antes de ser retomada, por outro nó
 *                       ou por este depois de reiniciar (OUTBOX_LEASE_SECONDS); nunca menos que
 *                       3x o timeout do webhook
 */
@ConfigurationProperties(prefix = "msgforge.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("24") int retentionHours,
        @DefaultValue("1000") long pollIntervalMs,
        @DefaultValue("64") int maxInFlight,
        @DefaultValue("60") int leaseSeconds) {}

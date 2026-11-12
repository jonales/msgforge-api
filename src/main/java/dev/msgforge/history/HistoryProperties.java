package dev.msgforge.history;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Histórico de mensagens no banco (ADR 0008).
 *
 * @param enabled       grava as mensagens enviadas e recebidas (DATABASE_SAVE_DATA_NEW_MESSAGE)
 * @param retentionDays apaga mensagens mais velhas que isso, pela data da mensagem
 *                      (HISTORY_RETENTION_DAYS); 0 guarda para sempre
 */
@ConfigurationProperties(prefix = "msgforge.history")
public record HistoryProperties(@DefaultValue("true") boolean enabled, @DefaultValue("0") int retentionDays) {}

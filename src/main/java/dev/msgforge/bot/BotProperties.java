// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.bot;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bot com IA (ADR 0015).
 *
 * @param enabled              liga os bots do servidor (BOT_ENABLED); false desliga todos
 * @param allowPrivateNetworks aceita o modelo num endereço privado, como um Ollama na mesma máquina
 *                             (BOT_ALLOW_PRIVATE_NETWORKS)
 * @param timeoutMs            tempo máximo de uma resposta do modelo (BOT_TIMEOUT_MS)
 * @param echoWindowMs         depois de o bot enviar, uma mensagem da própria conta na conversa é o eco
 *                             do envio, e não um atendente
 */
@ConfigurationProperties(prefix = "msgforge.bot")
public record BotProperties(@DefaultValue("true") boolean enabled,
                            @DefaultValue("false") boolean allowPrivateNetworks,
                            @DefaultValue("60000") int timeoutMs,
                            @DefaultValue("15000") long echoWindowMs) {}

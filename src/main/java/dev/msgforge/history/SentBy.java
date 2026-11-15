// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.history;

import java.util.UUID;

/**
 * Quem enviou uma mensagem pela Msgforge: um usuário do Manager, o Manager pela chave global, ou a API
 * (chave global ou token da instância), ou o bot da instância (ADR 0015). {@code name} é o nome na hora do envio (nome de exibição ou
 * e-mail); fica copiado no histórico.
 */
public record SentBy(Kind kind, UUID userId, String name) {

    public enum Kind { USER, MANAGER_KEY, API_KEY, API_TOKEN, BOT }

    public static final SentBy MANAGER_KEY = new SentBy(Kind.MANAGER_KEY, null, null);
    public static final SentBy API_KEY = new SentBy(Kind.API_KEY, null, null);
    public static final SentBy API_TOKEN = new SentBy(Kind.API_TOKEN, null, null);

    public static SentBy user(UUID userId, String name) {
        return new SentBy(Kind.USER, java.util.Objects.requireNonNull(userId, "userId"), name);
    }
}

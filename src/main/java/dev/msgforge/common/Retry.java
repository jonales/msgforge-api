// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.common;

import java.time.Duration;
import java.util.function.BiConsumer;

/**
 * Repetição curta para escritas que falham por um motivo passageiro (a conexão do banco que caiu
 * junto com a rede, depois de o computador dormir: o pool descarta a conexão quebrada e a próxima
 * tentativa pega uma nova). A espera cresce a cada tentativa ({@code delay}, {@code 2 * delay}...).
 */
public final class Retry {

    private Retry() {
    }

    /**
     * Roda {@code action} até dar certo ou acabarem as tentativas.
     *
     * @param attempts  quantas vezes tentar (pelo menos 1)
     * @param delay     a espera depois da primeira falha; cresce a cada tentativa
     * @param action    o que fazer
     * @param onFailure chamada a cada falha, com o número da tentativa e o erro
     * @return true se alguma tentativa deu certo; false se todas falharam (ou se a thread foi
     *         interrompida na espera, que fica marcada de novo)
     */
    public static boolean run(int attempts, Duration delay, Runnable action, BiConsumer<Integer, RuntimeException> onFailure) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts precisa ser pelo menos 1");
        }
        for (var attempt = 1; ; attempt++) {
            try {
                action.run();
                return true;
            } catch (RuntimeException e) {
                onFailure.accept(attempt, e);
                if (attempt >= attempts) {
                    return false;
                }
            }
            try {
                Thread.sleep(delay.multipliedBy(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.gateway.cobalt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Filtra as ações de app state (arquivar, fixar, silenciar, apagar conversa...) de uma conexão antes
 * de virarem CHATS_UPDATE/CHATS_DELETE. O motor entrega cada ação sem dizer se veio de uma alteração
 * nova ou de um retrato da coleção inteira (ao parear, ou quando uma alteração faz o motor baixar a
 * coleção); um retrato soltava um evento por conversa (85 ao parear, no teste de 2026-11-15).
 *
 * <p>As ações chegam em lotes ({@link #add}, e quem chama agenda o {@link #flush} um instante
 * depois). Num lote grande (mais de {@value #SNAPSHOT_SIZE} conversas), só sai o que mudou em
 * relação a um estado já visto; o resto só é memorizado. Num lote pequeno, sai tudo o que é novo ou
 * mudou. A ação que repete o estado já visto nunca sai. Sem estado (null), sempre sai num lote
 * pequeno.
 */
final class AppStateFilter {

    static final int SNAPSHOT_SIZE = 5;

    private record Item(String index, String state, Runnable emit) {}

    private final Map<String, String> seen = new ConcurrentHashMap<>();
    private final List<Item> pending = new ArrayList<>();

    /** @return true se esta é a primeira ação do lote (quem chama agenda o {@link #flush}) */
    synchronized boolean add(String index, String state, Runnable emit) {
        pending.add(new Item(index, state, emit));
        return pending.size() == 1;
    }

    /** Fecha o lote e devolve os eventos que devem sair, na ordem em que chegaram. */
    synchronized List<Runnable> flush() {
        var indexes = new HashSet<String>();
        pending.forEach(item -> indexes.add(item.index()));
        var snapshot = indexes.size() > SNAPSHOT_SIZE;
        var out = new ArrayList<Runnable>();
        for (var item : pending) {
            var previous = item.index() == null || item.state() == null ? null : seen.put(item.index(), item.state());
            if (item.state() != null && item.state().equals(previous)) {
                continue;   // mesmo estado de antes: ressincronização
            }
            if (snapshot && previous == null) {
                continue;   // retrato: estado sem histórico não é uma mudança
            }
            out.add(item.emit());
        }
        pending.clear();
        return out;
    }
}

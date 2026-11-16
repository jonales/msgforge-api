// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.gateway.cobalt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppStateFilterTest {

    private final List<String> out = new ArrayList<>();

    private void add(AppStateFilter filter, String index, String state) {
        filter.add(index, state, () -> out.add(index + "=" + state));
    }

    private void flush(AppStateFilter filter) {
        filter.flush().forEach(Runnable::run);
    }

    @Test
    void aSnapshotOnlyEmitsWhatChangedSinceAKnownState() {
        var filter = new AppStateFilter();
        // Ao parear: o retrato da coleção (8 conversas) não vira nenhum evento
        for (int i = 0; i < 8; i++) {
            add(filter, "[\"archive\",\"c" + i + "\"]", "archive:false");
        }
        flush(filter);
        assertThat(out).isEmpty();

        // A pessoa arquiva uma conversa: um evento
        add(filter, "[\"archive\",\"c3\"]", "archive:true");
        flush(filter);
        assertThat(out).containsExactly("[\"archive\",\"c3\"]=archive:true");

        // Novo retrato depois disso (a alteração fez o motor baixar a coleção): nada repetido
        out.clear();
        for (int i = 0; i < 8; i++) {
            add(filter, "[\"archive\",\"c" + i + "\"]", i == 3 ? "archive:true" : "archive:false");
        }
        flush(filter);
        assertThat(out).isEmpty();
    }

    @Test
    void aSmallBatchEmitsNewStatesAndActionsWithoutState() {
        var filter = new AppStateFilter();
        add(filter, "[\"pin\",\"a\"]", "pin:true");
        add(filter, "[\"clearChat\",\"a\"]", null);
        flush(filter);
        assertThat(out).containsExactly("[\"pin\",\"a\"]=pin:true", "[\"clearChat\",\"a\"]=null");
        out.clear();
        add(filter, "[\"pin\",\"a\"]", "pin:true");   // repetido
        flush(filter);
        assertThat(out).isEmpty();
    }
}

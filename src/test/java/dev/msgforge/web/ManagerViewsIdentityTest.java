// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Número legível e iniciais do avatar, usados na lista de instâncias e na Visão geral. */
class ManagerViewsIdentityTest {

    @Test
    void brazilianNumbersAreGroupedLikeOnThePhone() {
        assertThat(ManagerViews.formatNumber("5561984622220")).isEqualTo("+55 61 98462-2220");  // celular
        assertThat(ManagerViews.formatNumber("556133334444")).isEqualTo("+55 61 3333-4444");    // fixo
        assertThat(ManagerViews.formatNumber("556184622220")).isEqualTo("+55 61 8462-2220");    // celular sem o 9
    }

    @Test
    void otherNumbersKeepTheirDigitsAndLidsComeAsTheyAre() {
        assertThat(ManagerViews.formatNumber("14155550100")).isEqualTo("+14155550100");
        assertThat(ManagerViews.formatNumber("123456789012345@lid")).isEqualTo("123456789012345@lid");
        assertThat(ManagerViews.formatNumber(null)).isNull();
        assertThat(ManagerViews.formatNumber(" ")).isNull();
    }

    @Test
    void initialsComeFromTheFirstAndLastWordWithoutAccents() {
        assertThat(ManagerViews.initialsOf("Jônatas Meireles", "TESTE-1")).isEqualTo("JM");
        assertThat(ManagerViews.initialsOf("Ana", "vendas")).isEqualTo("A");
        assertThat(ManagerViews.initialsOf("Érica da Silva Souza", null)).isEqualTo("ES");
        assertThat(ManagerViews.initialsOf("4C3RT0 Tecnologia", "x")).isEqualTo("T");
    }

    @Test
    void withoutAProfileNameTheInstanceNameIsUsedAndNothingUsableGivesAQuestionMark() {
        assertThat(ManagerViews.initialsOf(null, "vendas-sul")).isEqualTo("VS");
        assertThat(ManagerViews.initialsOf("🙂🙂", "suporte")).isEqualTo("S");
        assertThat(ManagerViews.initialsOf("123", "456")).isEqualTo("?");
    }
}

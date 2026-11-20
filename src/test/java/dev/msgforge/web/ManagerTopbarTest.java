// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** O item atual do menu lateral fora da instância, pela rota. */
class ManagerTopbarTest {

    @Test
    void instancesCoverTheHomeAndTheInstanceRoutes() {
        assertThat(ManagerTopbar.section("/manager")).isEqualTo("instances");
        assertThat(ManagerTopbar.section("/manager/")).isEqualTo("instances");
        assertThat(ManagerTopbar.section("/manager/instances/new")).isEqualTo("instances");
        assertThat(ManagerTopbar.section("/manager/instances/vendas/connect")).isEqualTo("instances");
    }

    @Test
    void eachSectionMatchesItsOwnRoutesOnly() {
        assertThat(ManagerTopbar.section("/manager/users")).isEqualTo("users");
        assertThat(ManagerTopbar.section("/manager/roles/12")).isEqualTo("roles");
        assertThat(ManagerTopbar.section("/manager/audit")).isEqualTo("audit");
        assertThat(ManagerTopbar.section("/manager/server")).isEqualTo("server");
        assertThat(ManagerTopbar.section("/manager/usersx")).isNull();
        assertThat(ManagerTopbar.section("/manager/login")).isNull();
        assertThat(ManagerTopbar.section(null)).isNull();
    }
}

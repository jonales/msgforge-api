// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** O que entrou e saiu de um papel, como vai para a auditoria (ADR 0018). */
class PermissionDiffTest {

    @Test
    void listsAddedAndRemovedInCatalogOrder() {
        assertThat(AccessAdminService.permissionDiff(Set.of(Permission.USERS_MANAGE, Permission.INSTANCE_VIEW),
                Set.of(Permission.INSTANCE_VIEW, Permission.CHAT_SEND)))
                .isEqualTo("+CHAT_SEND -USERS_MANAGE");
    }

    @Test
    void nothingChangedIsNull() {
        assertThat(AccessAdminService.permissionDiff(Set.of(Permission.CHAT_VIEW), Set.of(Permission.CHAT_VIEW)))
                .isNull();
        assertThat(AccessAdminService.permissionDiff(Set.of(), Set.of())).isNull();
    }
}

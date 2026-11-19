// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.audit.AuditAction;
import dev.msgforge.audit.AuditEntry;
import dev.msgforge.audit.AuditLog;
import dev.msgforge.audit.AuditOutcome;
import dev.msgforge.web.AuditViews.AuditRow;
import dev.msgforge.web.AuditViews.DetailPart;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditViewsTest {

    private static AuditEntry entry(AuditAction action, String label, String detail) {
        return new AuditEntry(1, Instant.parse("2026-11-19T22:00:00Z"), action, AuditOutcome.OK, null, null, label,
                "Plantão", detail, "203.0.113.7");
    }

    @Test
    void aRoleDiffBecomesTranslatablePermissions() {
        assertThat(AuditViews.detailOf(entry(AuditAction.ROLE_UPDATED, "ana@exemplo.com", "+CHAT_SEND -USERS_MANAGE")))
                .containsExactly(new DetailPart(true, "CHAT_SEND", null), new DetailPart(false, "USERS_MANAGE", null));
    }

    @Test
    void anythingElseStaysAsText() {
        // Nome de papel parecido com o formato, numa ação que não é de papel, ou permissão que não existe
        assertThat(AuditViews.detailOf(entry(AuditAction.USER_ROLE_CHANGED, "a@b.c", "Atendente → Operador")))
                .containsExactly(new DetailPart(false, null, "Atendente → Operador"));
        assertThat(AuditViews.detailOf(entry(AuditAction.ROLE_UPDATED, "a@b.c", "+NAO_EXISTE")))
                .containsExactly(new DetailPart(false, null, "+NAO_EXISTE"));
        assertThat(AuditViews.detailOf(entry(AuditAction.ROLE_DELETED, "a@b.c", null))).isEmpty();
    }

    @Test
    void theActorKindComesFromTheLabel() {
        assertThat(AuditRow.of(entry(AuditAction.LOGIN, AuditLog.GLOBAL_KEY, null), Map.of(), false))
                .satisfies(r -> {
                    assertThat(r.actorKind()).isEqualTo("globalKey");
                    assertThat(r.actor()).isNull();
                });
        assertThat(AuditRow.of(entry(AuditAction.LOGIN, AuditLog.UNKNOWN_ACCOUNT, null), Map.of(), false).actorKind())
                .isEqualTo("unknown");
        assertThat(AuditRow.of(entry(AuditAction.LOGIN, "ana@exemplo.com", null), Map.of(), false).actor())
                .isEqualTo("ana@exemplo.com");
    }

    @Test
    void theOrganizationNameOnlyShowsOnThePlatformView() {
        var org = UUID.randomUUID();
        var e = new AuditEntry(1, Instant.now(), AuditAction.ROLE_DELETED, AuditOutcome.OK, org, null, "a@b.c",
                "x", null, null);
        assertThat(AuditRow.of(e, Map.of(org, "Clínica"), true).organization()).isEqualTo("Clínica");
        assertThat(AuditRow.of(e, Map.of(org, "Clínica"), false).organization()).isNull();
    }
}

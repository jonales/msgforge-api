// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.access.ManagerUser;
import dev.msgforge.history.SentBy;
import dev.msgforge.instance.Instance;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttendantSignatureTest {

    @Test
    void displayNamesAreOneLineWithoutWhatsAppFormatting() {
        assertThat(ManagerUser.normalizeDisplayName("  Ana   Souza ")).isEqualTo("Ana Souza");
        assertThat(ManagerUser.normalizeDisplayName("*Ana*\n_Souza_~`")).isEqualTo("Ana Souza");
        assertThat(ManagerUser.normalizeDisplayName("Ana\r\nSouza\t")).isEqualTo("Ana Souza");
        assertThat(ManagerUser.normalizeDisplayName("***")).isNull();
        assertThat(ManagerUser.normalizeDisplayName("  ")).isNull();
        assertThat(ManagerUser.normalizeDisplayName(null)).isNull();
        assertThat(ManagerUser.normalizeDisplayName("x".repeat(60))).hasSize(60);
        assertThat(ManagerUser.normalizeDisplayName("x".repeat(61))).isNull();
    }

    @Test
    void theReplyIsSignedOnlyWhenTheInstanceSignsAndThereIsAName() {
        var instance = new Instance("loja", "token", "WHATSAPP-BAILEYS", null);
        var ana = new ManagerAuthor.Sender(SentBy.user(UUID.randomUUID(), "Ana"), "Ana");
        var noName = new ManagerAuthor.Sender(SentBy.user(UUID.randomUUID(), "ana@exemplo.com"), null);

        assertThat(ManagerAuthor.reply(instance, ana, "oi")).isEqualTo("oi");
        instance.setSignMessages(true);
        assertThat(ManagerAuthor.reply(instance, ana, "oi\ntudo bem?")).isEqualTo("*Atendente Ana:*\noi\ntudo bem?");
        assertThat(ManagerAuthor.reply(instance, noName, "oi")).isEqualTo("oi");
        assertThat(ManagerAuthor.reply(instance, new ManagerAuthor.Sender(SentBy.MANAGER_KEY, null), "oi"))
                .isEqualTo("oi");
    }

    @Test
    void theSignatureFollowsTheInstanceTemplate() {
        var instance = new Instance("loja", "token", "WHATSAPP-BAILEYS", null);
        instance.setSignMessages(true);
        instance.setSignatureTemplate("{nome} (Suporte)");
        var ana = new ManagerAuthor.Sender(SentBy.user(UUID.randomUUID(), "Ana"), "Ana");
        assertThat(ManagerAuthor.reply(instance, ana, "oi")).isEqualTo("*Ana (Suporte):*\noi");

        assertThat(dev.msgforge.instance.InstanceService.signatureTemplate("  Consultor   *{nome}*  "))
                .isEqualTo("Consultor {nome}");
        assertThat(dev.msgforge.instance.InstanceService.signatureTemplate("")).isEqualTo("Atendente {nome}");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> dev.msgforge.instance.InstanceService.signatureTemplate("Atendente"))
                .isInstanceOf(dev.msgforge.common.ApiExceptionHandler.InvalidFieldException.class);
    }
}

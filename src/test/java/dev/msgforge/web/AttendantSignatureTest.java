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
        assertThat(ManagerAuthor.reply(instance, ana, "oi\ntudo bem?")).isEqualTo("*Ana*\noi\ntudo bem?");
        assertThat(ManagerAuthor.reply(instance, noName, "oi")).isEqualTo("oi");
        assertThat(ManagerAuthor.reply(instance, new ManagerAuthor.Sender(SentBy.MANAGER_KEY, null), "oi"))
                .isEqualTo("oi");
    }
}

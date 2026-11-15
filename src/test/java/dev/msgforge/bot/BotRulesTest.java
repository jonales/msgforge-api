package dev.msgforge.bot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.bot.BotRules.Action;
import dev.msgforge.bot.BotRules.Session;
import dev.msgforge.bot.BotRules.Settings;
import dev.msgforge.bot.BotRules.Status;
import dev.msgforge.bot.InstanceBot.ResumeMode;
import dev.msgforge.bot.InstanceBot.TriggerMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class BotRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    private static Settings settings(TriggerMode mode, String keywords, String stop, int sessionMinutes) {
        return new Settings(mode, BotRules.keywords(keywords), stop, sessionMinutes);
    }

    @Test
    void keywordsIgnoreAccentsCaseAndExtraSpaces() {
        assertThat(BotRules.keywords("Orçamento, PREÇO\n  horário  ,,")).containsExactly("orcamento", "preco", "horario");
        var keyword = settings(TriggerMode.KEYWORD, "orçamento", "", 0);
        assertThat(BotRules.onMessage(keyword, null, "Quero um ORCAMENTO", NOW).action()).isEqualTo(Action.ANSWER);
        assertThat(BotRules.onMessage(keyword, null, "bom dia", NOW).action()).isEqualTo(Action.IGNORE);
        var exact = settings(TriggerMode.EXACT, "1, atendimento", "", 0);
        assertThat(BotRules.onMessage(exact, null, " Atendimento ", NOW).action()).isEqualTo(Action.ANSWER);
        assertThat(BotRules.onMessage(exact, null, "quero atendimento", NOW).action()).isEqualTo(Action.IGNORE);
        assertThat(BotRules.onMessage(settings(TriggerMode.ALL, "", "", 0), null, "oi", NOW).action())
                .isEqualTo(Action.ANSWER);
    }

    @Test
    void onceInTheBotKeepsAnsweringUntilTheSessionExpires() {
        var keyword = settings(TriggerMode.KEYWORD, "orçamento", "", 30);
        var active = new Session(Status.ACTIVE, null, NOW.minus(Duration.ofMinutes(10)));
        var decision = BotRules.onMessage(keyword, active, "e o prazo?", NOW);
        assertThat(decision.action()).isEqualTo(Action.ANSWER);
        assertThat(decision.next()).isEqualTo(new Session(Status.ACTIVE, null, NOW));
        var stale = new Session(Status.ACTIVE, null, NOW.minus(Duration.ofMinutes(31)));
        assertThat(BotRules.onMessage(keyword, stale, "e o prazo?", NOW).action()).isEqualTo(Action.IGNORE);
        // 0 = não expira
        var forever = settings(TriggerMode.KEYWORD, "orçamento", "", 0);
        assertThat(BotRules.onMessage(forever, new Session(Status.ACTIVE, null, NOW.minus(Duration.ofDays(3))), "oi", NOW)
                .action()).isEqualTo(Action.ANSWER);
    }

    @Test
    void theStopWordClosesAndAllModeStaysClosedUntilReactivated() {
        var all = settings(TriggerMode.ALL, "", "Sair", 0);
        var active = new Session(Status.ACTIVE, null, NOW);
        var closed = BotRules.onMessage(all, active, " sair ", NOW);
        assertThat(closed.action()).isEqualTo(Action.CLOSE);
        assertThat(closed.next().status()).isEqualTo(Status.CLOSED);
        assertThat(BotRules.onMessage(all, closed.next(), "oi de novo", NOW).action()).isEqualTo(Action.IGNORE);
        // Com palavra-chave, a palavra reabre
        var keyword = settings(TriggerMode.KEYWORD, "orçamento", "sair", 0);
        assertThat(BotRules.onMessage(keyword, closed.next(), "outro orçamento", NOW).action()).isEqualTo(Action.ANSWER);
        // Palavra de encerrar fora de uma conversa com o bot: nada
        assertThat(BotRules.onMessage(all, null, "sair", NOW).action()).isEqualTo(Action.IGNORE);
    }

    @Test
    void aHumanReplyPausesForAWhileOrUntilReactivated() {
        var all = settings(TriggerMode.ALL, "", "", 0);
        var timed = BotRules.onHumanReply(ResumeMode.AUTOMATIC, 30, NOW);
        assertThat(timed.pausedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(BotRules.onMessage(all, timed, "oi", NOW.plus(Duration.ofMinutes(29))).action())
                .isEqualTo(Action.IGNORE);
        var back = BotRules.onMessage(all, timed, "oi", NOW.plus(Duration.ofMinutes(30)));
        assertThat(back.action()).isEqualTo(Action.ANSWER);
        // Pausa com prazo vencido volta mesmo no modo palavra-chave (a conversa já era do bot)
        var keyword = settings(TriggerMode.KEYWORD, "orçamento", "", 0);
        assertThat(BotRules.onMessage(keyword, timed, "e aí?", NOW.plus(Duration.ofHours(1))).action())
                .isEqualTo(Action.ANSWER);

        var manual = BotRules.onHumanReply(ResumeMode.MANUAL, 30, NOW);
        assertThat(manual.manualPause()).isTrue();
        assertThat(BotRules.onMessage(all, manual, "oi", NOW.plus(Duration.ofDays(30))).action())
                .isEqualTo(Action.IGNORE);
        assertThat(List.of(Status.values())).hasSize(3);
    }
}

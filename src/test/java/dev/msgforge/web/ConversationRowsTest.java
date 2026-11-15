// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.msgforge.web.ManagerViews.ChatList;
import dev.msgforge.web.ManagerViews.ChatRow;
import dev.msgforge.web.ManagerViews.MessageRow;
import dev.msgforge.web.ManagerViews.StoredRow;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Conversa: separador de dia, remetente só no começo de cada sequência e grupos à parte na lista. */
class ConversationRowsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    private static long at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    private static StoredRow row(String id, boolean fromMe, String pushName, String participant, long ts) {
        return new StoredRow(id, fromMe, pushName, participant, new MessageContent.Text(id), ts, "PENDING");
    }

    @Test
    void groupRunsAndDaySeparators() {
        var ana = "5561911111111@s.whatsapp.net";
        var lid = "222222222222222@lid";
        var rows = ManagerViews.rows(List.of(
                row("1", false, "Ana", ana, at(TODAY.minusDays(3), 9)),
                row("2", false, "Ana", ana, at(TODAY.minusDays(1), 9)),
                row("3", false, "Ana", ana, at(TODAY.minusDays(1), 10)),
                row("4", false, null, lid, at(TODAY.minusDays(1), 11)),
                row("5", true, "Eu", null, at(TODAY, 8)),
                row("6", true, "Eu", null, at(TODAY, 9))), true, TODAY);

        assertThat(rows).extracting(MessageRow::startsRun).containsExactly(true, true, false, true, true, false);
        assertThat(rows).extracting(r -> r.day() == null ? null : r.day().kind())
                .containsExactly("date", "yesterday", null, null, "today", null);
        assertThat(rows.getFirst().day().date()).isEqualTo("11/11/2026");

        assertThat(rows.get(0).authorLabel()).isEqualTo("Ana");
        assertThat(rows.get(0).authorNumber()).isEqualTo("+5561911111111");
        assertThat(rows.get(0).showNumber()).isTrue();
        // LID sem tradução fica como veio, nunca um telefone inventado
        assertThat(rows.get(3).authorLabel()).isEqualTo(lid);
        assertThat(rows.get(3).showNumber()).isFalse();
        // As próprias mensagens não levam participante
        assertThat(rows.get(4).authorJid()).isNull();
        assertThat(rows.get(4).time()).isEqualTo("08:00");
    }

    private static StoredRow reaction(String id, boolean fromMe, String pushName, String participant, String emoji,
                                      String target, long ts) {
        return new StoredRow(id, fromMe, pushName, participant, new MessageContent.Reaction(emoji, target), ts, "PENDING");
    }

    @Test
    void reactionsGoToTheTargetMessageAndTheLastOneOfEachPersonWins() {
        var ana = "5561911111111@s.whatsapp.net";
        var bia = "5561922222222@s.whatsapp.net";
        var rows = ManagerViews.rows(List.of(
                row("1", false, "Ana", ana, at(TODAY, 9)),
                row("2", true, null, null, at(TODAY, 9)),
                reaction("R1", false, "Ana", ana, "👍", "2", at(TODAY, 10)),
                reaction("R2", false, null, bia, "🙏", "2", at(TODAY, 10)),
                reaction("R3", false, "Ana", ana, "❤️", "2", at(TODAY, 11)),   // troca a da Ana
                reaction("R4", true, null, null, "😂", "1", at(TODAY, 11)),    // da própria conta (API ou celular)
                reaction("R5", false, null, bia, "", "2", at(TODAY, 12)),      // a Bia tira a dela
                reaction("R6", true, null, null, "🔥", "fora-da-pagina", at(TODAY, 12)),
                reaction("R7", false, "Ana", ana, "👀", null, at(TODAY, 12)),
                row("3", false, "Ana", ana, at(TODAY, 13))), true, TODAY);

        // Nenhuma reação vira balão; a sequência e o separador ignoram as reações
        assertThat(rows).extracting(MessageRow::id).containsExactly("1", "2", "3");
        assertThat(rows).extracting(MessageRow::startsRun).containsExactly(true, true, true);
        assertThat(rows.get(1).reactions()).containsExactly(new ManagerViews.ReactionChip("❤️", false, "Ana"));
        assertThat(rows.get(0).reactions()).containsExactly(new ManagerViews.ReactionChip("😂", true, null));
        assertThat(rows.get(2).reactions()).isEmpty();

        // Sem pushName, em grupo, quem reagiu é o número
        var other = ManagerViews.rows(List.of(row("1", true, null, null, at(TODAY, 9)),
                reaction("R1", false, null, bia, "🙏", "1", at(TODAY, 10))), true, TODAY);
        assertThat(other.getFirst().reactions()).containsExactly(new ManagerViews.ReactionChip("🙏", false, "+5561922222222"));
    }

    @Test
    void theChatPreviewOfAReactionQuotesTheTarget() {
        var target = new MessageContent.Text("vamos marcar a reunião de amanhã cedo, pode ser às 9h?");
        var row = new ChatRow("5561911111111@s.whatsapp.net", "Ana", new MessageContent.Reaction("🙏", "X"), "",
                false, null, target);
        assertThat(row.reaction()).isTrue();
        assertThat(row.reactedSnippet()).isEqualTo("vamos marcar a reunião de amanhã cedo, p…");
        // Reação removida, ou alvo fora do histórico: a prévia de sempre
        assertThat(new ChatRow("x", null, new MessageContent.Reaction("", "X"), "", false, null, target).reaction())
                .isFalse();
        assertThat(new ChatRow("x", null, new MessageContent.Reaction("🙏", "X"), "", false, null).reaction()).isFalse();
    }

    @Test
    void inADirectChatTheSideIsTheSender() {
        var rows = ManagerViews.rows(List.of(
                row("1", false, "Ana", null, at(TODAY, 9)),
                row("2", false, "Ana", null, at(TODAY, 9)),
                row("3", true, null, null, at(TODAY, 10)),
                row("4", false, "Ana", null, at(TODAY, 11))), false, TODAY);
        assertThat(rows).extracting(MessageRow::startsRun).containsExactly(true, false, true, true);
        assertThat(rows).allMatch(r -> r.authorJid() == null);
    }

    @Test
    void chatListSplitsPeopleFromGroups() {
        var text = new MessageContent.Text("oi");
        var list = ChatList.of(List.of(
                new ChatRow("120363000000000000@g.us", "Equipe", text, "", false, "Ana"),
                new ChatRow("5561911111111@s.whatsapp.net", null, text, "", false, null),
                new ChatRow("120363000000000001@g.us", null, text, "", true, null),
                new ChatRow("222222222222222@lid", null, text, "", false, null)));
        assertThat(list.people()).extracting(ChatRow::label).containsExactly("+5561911111111", "222222222222222@lid");
        assertThat(list.groups()).extracting(ChatRow::label).containsExactly("Equipe", "120363000000000001@g.us");
        assertThat(list.size()).isEqualTo(4);
        assertThat(ChatList.of(List.of()).empty()).isTrue();
    }
}

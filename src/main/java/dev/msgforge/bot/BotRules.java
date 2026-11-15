// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Jônatas Meireles
package dev.msgforge.bot;

import dev.msgforge.bot.InstanceBot.ResumeMode;
import dev.msgforge.bot.InstanceBot.TriggerMode;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * As regras do bot, sem banco nem rede (ADR 0015): se uma mensagem recebida faz o bot responder,
 * encerrar ou ficar quieto, e o estado da conversa depois disso.
 */
public final class BotRules {

    private BotRules() {}

    public enum Status { ACTIVE, PAUSED, CLOSED }

    /** O bot numa conversa. {@code pausedUntil} nulo numa pausa = só volta reativado à mão. */
    public record Session(Status status, Instant pausedUntil, Instant lastActivity) {

        public boolean manualPause() {
            return status == Status.PAUSED && pausedUntil == null;
        }
    }

    public enum Action { ANSWER, CLOSE, IGNORE }

    /** O que fazer e como fica a conversa ({@code next} nulo = não muda nada). */
    public record Decision(Action action, Session next) {}

    /** O que o bot precisa saber da configuração para decidir. */
    public record Settings(TriggerMode trigger, List<String> keywords, String stopKeyword, int sessionMinutes) {

        static Settings of(InstanceBot bot) {
            return new Settings(bot.getTriggerMode(), BotRules.keywords(bot.getKeywords()), bot.getStopKeyword(),
                    bot.getSessionMinutes());
        }
    }

    /** Uma mensagem de texto recebida numa conversa. */
    public static Decision onMessage(Settings settings, Session current, String text, Instant now) {
        var session = current;
        if (session != null && session.status() == Status.PAUSED) {
            if (session.pausedUntil() == null || now.isBefore(session.pausedUntil())) {
                return new Decision(Action.IGNORE, null);
            }
            session = new Session(Status.ACTIVE, null, now);   // a pausa com prazo acabou: o bot volta
        }
        var active = session != null && session.status() == Status.ACTIVE
                && (settings.sessionMinutes() == 0
                    || now.isBefore(session.lastActivity().plus(Duration.ofMinutes(settings.sessionMinutes()))));
        if (!settings.stopKeyword().isBlank() && normalize(text).equals(normalize(settings.stopKeyword()))) {
            return active ? new Decision(Action.CLOSE, new Session(Status.CLOSED, null, now))
                    : new Decision(Action.IGNORE, null);
        }
        if (!active && session != null && session.status() == Status.CLOSED && settings.trigger() == TriggerMode.ALL) {
            return new Decision(Action.IGNORE, null);   // encerrada no modo "todas": só volta reativada
        }
        if (!active && !triggers(settings.trigger(), settings.keywords(), text)) {
            return new Decision(Action.IGNORE, null);
        }
        return new Decision(Action.ANSWER, new Session(Status.ACTIVE, null, now));
    }

    /** Um atendente respondeu (Manager ou celular): o bot sai da conversa até a hora ou até reativarem. */
    public static Session onHumanReply(ResumeMode mode, int pauseMinutes, Instant now) {
        return new Session(Status.PAUSED,
                mode == ResumeMode.AUTOMATIC ? now.plus(Duration.ofMinutes(pauseMinutes)) : null, now);
    }

    static boolean triggers(TriggerMode mode, List<String> keywords, String text) {
        var message = normalize(text);
        return switch (mode) {
            case ALL -> true;
            case KEYWORD -> keywords.stream().anyMatch(message::contains);
            case EXACT -> keywords.stream().anyMatch(message::equals);
        };
    }

    /** Palavras-chave separadas por vírgula ou linha, já normalizadas, sem as vazias. */
    public static List<String> keywords(String raw) {
        if (raw == null) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,\\n]")).map(BotRules::normalize).filter(k -> !k.isEmpty()).distinct()
                .toList();
    }

    /** Sem acento, minúsculas, espaços simples: "Olá  Mundo" e "ola mundo" são iguais. */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        var plain = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
